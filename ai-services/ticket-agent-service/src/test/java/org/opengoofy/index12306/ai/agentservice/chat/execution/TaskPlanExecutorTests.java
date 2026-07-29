package org.opengoofy.index12306.ai.agentservice.chat.execution;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentChatProperties;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskPlan;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskSlots;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.WorkflowRelation;
import org.opengoofy.index12306.ai.agentservice.chat.observability.AgentChatMetrics;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证多任务调度的并发、交易屏障和失败隔离规则。
 */
class TaskPlanExecutorTests {

    /**
     * 验证两个独立查询并行执行，而交易任务等待全部查询完成。
     */
    @Test
    void independentReadsRunInParallelBeforeTransaction() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TaskPlanExecutor service =
                service(Duration.ofSeconds(1), 4, meterRegistry);
        TaskPlan plan = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.TRAIN_QUERY, List.of()),
                task("task-2", 2, AgentIntent.PASSENGER_QUERY, List.of()),
                task("task-3", 3, AgentIntent.TICKET_PURCHASE, List.of("task-1"))));
        AtomicInteger activeReads = new AtomicInteger();
        AtomicInteger maxActiveReads = new AtomicInteger();
        AtomicInteger completedReads = new AtomicInteger();
        AtomicBoolean transactionSawAllReads = new AtomicBoolean();

        StepVerifier.create(service.execute(plan, (task, dependencies) -> {
                    if (task.intent() == AgentIntent.TICKET_PURCHASE) {
                        // 交易执行时两个只读任务都必须已经到达终态。
                        transactionSawAllReads.set(completedReads.get() == 2);
                        return Mono.just(success(task, "transaction"));
                    }
                    return Mono.defer(() -> {
                                int active = activeReads.incrementAndGet();
                                maxActiveReads.accumulateAndGet(active, Math::max);
                                return Mono.delay(Duration.ofMillis(40))
                                        .map(ignored -> success(task, task.taskId()));
                            })
                            .doOnSuccess(ignored -> completedReads.incrementAndGet())
                            .doFinally(ignored -> activeReads.decrementAndGet());
                }))
                .assertNext(summary -> {
                    assertThat(summary.results())
                            .extracting(TaskExecutionResult::taskId)
                            .containsExactly("task-1", "task-2", "task-3");
                    assertThat(maxActiveReads.get()).isEqualTo(2);
                    assertThat(transactionSawAllReads).isTrue();
                })
                .verifyComplete();
    }

    /**
     * 验证显式依赖失败时后续任务被阻塞，其他任务异常不会向下游泄露。
     */
    @Test
    void failedDependencyBlocksDependentTask() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TaskPlanExecutor service =
                service(Duration.ofSeconds(1), 4, meterRegistry);
        TaskPlan plan = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.TRAIN_QUERY, List.of()),
                task("task-2", 2, AgentIntent.TRAIN_STOP_QUERY, List.of("task-1"))));
        AtomicInteger runnerCalls = new AtomicInteger();

        StepVerifier.create(service.execute(plan, (task, dependencies) -> {
                    runnerCalls.incrementAndGet();
                    return Mono.error(new IllegalStateException("internal detail"));
                }))
                .assertNext(summary -> {
                    assertThat(summary.results().get(0).status()).isEqualTo(TaskExecutionStatus.FAILED);
                    assertThat(summary.results().get(0).content()).doesNotContain("internal detail");
                    assertThat(summary.results().get(1).status()).isEqualTo(TaskExecutionStatus.BLOCKED);
                    assertThat(runnerCalls.get()).isEqualTo(1);
                })
                .verifyComplete();
    }

    /**
     * 验证单个只读任务超时只影响自身和显式依赖任务，独立查询仍能返回成功结果。
     */
    @Test
    void timedOutReadIsIsolatedAndRecorded() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TaskPlanExecutor service =
                service(Duration.ofMillis(30), 2, meterRegistry);
        TaskPlan plan = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.TRAIN_QUERY, List.of()),
                task("task-2", 2, AgentIntent.PASSENGER_QUERY, List.of())));

        StepVerifier.create(service.execute(plan, (task, dependencies) -> {
                    if (task.taskId().equals("task-1")) {
                        // 永不完成的只读调用用于验证任务级超时，不依赖真实网络时钟。
                        return Mono.<TaskExecutionResult>never();
                    }
                    return Mono.just(success(task, "passengers"));
                }))
                .assertNext(summary -> {
                    assertThat(summary.results())
                            .extracting(TaskExecutionResult::status)
                            .containsExactly(
                                    TaskExecutionStatus.TIMED_OUT,
                                    TaskExecutionStatus.SUCCESS);
                    assertThat(meterRegistry
                            .get("agent.chat.task.executions")
                            .tag("intent", "TRAIN_QUERY")
                            .tag("outcome", "TIMED_OUT")
                            .counter()
                            .count()).isEqualTo(1);
                    assertThat(meterRegistry
                            .get("agent.chat.task.duration")
                            .tag("intent", "PASSENGER_QUERY")
                            .tag("outcome", "SUCCESS")
                            .timer()
                            .count()).isEqualTo(1);
                })
                .verifyComplete();
    }

    /**
     * 验证交易固定链不使用可能提前取消订阅的只读任务超时。
     */
    @Test
    void transactionIsNotCutOffByReadTimeout() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TaskPlanExecutor service =
                service(Duration.ofMillis(10), 1, meterRegistry);
        TaskPlan plan = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.TICKET_PURCHASE, List.of())));

        StepVerifier.create(service.execute(plan, (task, dependencies) ->
                        Mono.delay(Duration.ofMillis(40))
                                .map(ignored -> success(task, "draft"))))
                .assertNext(summary -> assertThat(summary.results().get(0).status())
                        .isEqualTo(TaskExecutionStatus.SUCCESS))
                .verifyComplete();
    }

    /**
     * 验证配置的最大并发数会限制同一轮独立只读任务的同时执行数量。
     */
    @Test
    void configuredConcurrencyLimitIsRespected() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TaskPlanExecutor service =
                service(Duration.ofSeconds(1), 1, meterRegistry);
        TaskPlan plan = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.TRAIN_QUERY, List.of()),
                task("task-2", 2, AgentIntent.PASSENGER_QUERY, List.of()),
                task("task-3", 3, AgentIntent.ORDER_QUERY, List.of())));
        AtomicInteger activeTasks = new AtomicInteger();
        AtomicInteger maxActiveTasks = new AtomicInteger();

        StepVerifier.create(service.execute(plan, (task, dependencies) ->
                        Mono.defer(() -> {
                                    int active = activeTasks.incrementAndGet();
                                    maxActiveTasks.accumulateAndGet(active, Math::max);
                                    return Mono.delay(Duration.ofMillis(20))
                                            .map(ignored -> {
                                                // 在结果发给 flatMap 前结束工作计数，避免终态回调时序造成虚假重叠。
                                                activeTasks.decrementAndGet();
                                                return success(task, task.taskId());
                                            });
                                })))
                .assertNext(summary -> {
                    assertThat(summary.results()).hasSize(3);
                    assertThat(maxActiveTasks.get()).isEqualTo(1);
                })
                .verifyComplete();
    }

    /**
     * 创建带测试配置和独立指标注册表的任务执行器。
     *
     * @param readTaskTimeout 单个只读任务超时
     * @param maxConcurrency 最大并发任务数
     * @param meterRegistry 测试指标注册表
     * @return 可直接执行测试计划的调度器
     */
    private TaskPlanExecutor service(
            Duration readTaskTimeout,
            int maxConcurrency,
            SimpleMeterRegistry meterRegistry) {
        // 整轮超时由上层服务负责，本测试只配置调度器直接使用的任务参数。
        AgentChatProperties properties = new AgentChatProperties(
                Duration.ofSeconds(5),
                readTaskTimeout,
                maxConcurrency);
        return new TaskPlanExecutor(
                properties,
                new AgentChatMetrics(meterRegistry));
    }

    /**
     * 创建调度测试任务。
     *
     * @param taskId 任务标识
     * @param sequence 用户表达顺序
     * @param intent 当前意图
     * @param dependencies 显式依赖
     * @return 调度器可执行任务
     */
    private PlannedTask task(
            String taskId,
            int sequence,
            AgentIntent intent,
            List<String> dependencies) {
        // 调度测试不依赖具体业务槽位，只验证意图和依赖图。
        return new PlannedTask(
                taskId,
                sequence,
                intent,
                taskId,
                taskId,
                new TaskSlots(
                        null, null, null, null, null, null, null, List.of(), null, null),
                List.of(),
                dependencies,
                WorkflowRelation.INDEPENDENT,
                List.of());
    }

    /**
     * 创建稳定的成功执行结果。
     *
     * @param task 当前任务
     * @param content 测试结果正文
     * @return 成功任务结果
     */
    private TaskExecutionResult success(
            PlannedTask task,
            String content) {
        // 结果字段保持与生产执行器一致，便于验证最终排序。
        return new TaskExecutionResult(
                task.taskId(),
                task.sequence(),
                task.intent(),
                TaskExecutionStatus.SUCCESS,
                task.standaloneQuestion(),
                content,
                List.of(),
                null,
                null);
    }
}
