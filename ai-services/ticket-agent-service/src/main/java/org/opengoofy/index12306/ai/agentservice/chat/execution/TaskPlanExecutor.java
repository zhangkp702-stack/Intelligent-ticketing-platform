package org.opengoofy.index12306.ai.agentservice.chat.execution;

import org.opengoofy.index12306.ai.agentservice.chat.config.AgentChatProperties;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionSummary;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskPlan;
import org.opengoofy.index12306.ai.agentservice.chat.observability.AgentChatMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 按任务依赖图调度只读任务和固定交易任务。
 */
@Service
public class TaskPlanExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskPlanExecutor.class);
    private static final Set<AgentIntent> TRANSACTION_INTENTS = Set.of(
            AgentIntent.TICKET_PURCHASE,
            AgentIntent.ORDER_CANCELLATION,
            AgentIntent.TICKET_REFUND);
    private final AgentChatProperties properties;
    private final AgentChatMetrics chatMetrics;

    /**
     * 创建具有受控并发、只读超时和任务指标的计划执行器。
     *
     * @param properties 对话和任务调度配置
     * @param chatMetrics 低基数任务指标记录器
     */
    public TaskPlanExecutor(
            AgentChatProperties properties,
            AgentChatMetrics chatMetrics) {
        this.properties = properties;
        this.chatMetrics = chatMetrics;
    }

    /**
     * 按依赖关系并行执行任务，并在所有任务结束后恢复用户原始顺序。
     *
     * @param plan 已通过确定性校验的任务计划
     * @param taskRunner 实际执行单个任务的业务函数
     * @return 所有任务均达到终态后的异步汇总
     */
    public Mono<TaskExecutionSummary> execute(
            TaskPlan plan,
            TaskRunner taskRunner) {
        // 不需要持久化检查点的单元测试和纯执行调用沿用直接生命周期。
        return execute(plan, taskRunner, (task, execution) -> execution.get());
    }

    /**
     * 按依赖关系执行任务，并允许调用方为每个任务包装持久化领取和提交生命周期。
     *
     * @param plan 已通过确定性校验的任务计划
     * @param taskRunner 实际执行单个任务的业务函数
     * @param lifecycle 单任务持久化生命周期
     * @return 所有任务均达到终态后的异步汇总
     */
    public Mono<TaskExecutionSummary> execute(
            TaskPlan plan,
            TaskRunner taskRunner,
            TaskExecutionLifecycle lifecycle) {
        if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
            return Mono.error(new IllegalArgumentException("任务计划不能为空"));
        }
        if (taskRunner == null) {
            return Mono.error(new IllegalArgumentException("任务执行器不能为空"));
        }
        if (lifecycle == null) {
            return Mono.error(new IllegalArgumentException("任务生命周期不能为空"));
        }

        // 每个任务 Mono 只允许执行一次，依赖订阅和最终汇总共享同一个缓存结果。
        // 任务索引表
        Map<String, PlannedTask> taskById = new HashMap<>();
        // taskId → PlannedTask
        plan.tasks().forEach(task -> taskById.put(task.taskId(), task));

        // 执行流缓存表
        Map<String, Mono<TaskExecutionResult>> executions = new HashMap<>();

        // 获取所有的查询类的任务
        List<String> readTaskIds = plan.tasks().stream()
                // 过滤，只保留非交易意图清单
                .filter(task -> !isTransaction(task.intent()))
                // 转成taskid存储起来
                .map(PlannedTask::taskId)
                .toList();
        // 为每个任务建立执行流
        for (PlannedTask task : plan.tasks()) {
            // 递归建立依赖 Mono，使排在交易任务之后的独立查询也能进入只读屏障。
            createExecution(task, taskById, readTaskIds, executions, taskRunner, lifecycle);
        }

        // flatMap 触发互不依赖任务并行订阅，结果在全部完成后按 sequence 重新排序。
        Mono<TaskExecutionSummary> summary = Flux.fromIterable(plan.tasks())
                .flatMap(
                        task -> executions.get(task.taskId()),
                        properties.taskMaxConcurrency())
                .collectList()
                .map(TaskExecutionSummary::ordered);
        return summary;
    }

    /**
     * 递归创建并缓存单个任务及其依赖的执行 Mono。
     *
     * @param task 当前任务
     * @param taskById 当前计划任务索引
     * @param readTaskIds 当前计划的只读任务标识
     * @param executions 已创建的共享执行 Mono
     * @param taskRunner 实际业务执行函数
     * @param lifecycle 单任务持久化生命周期
     * @return 当前任务只执行一次的缓存 Mono
     */
    private Mono<TaskExecutionResult> createExecution(
            PlannedTask task,
            Map<String, PlannedTask> taskById,
            List<String> readTaskIds,
            Map<String, Mono<TaskExecutionResult>> executions,
            TaskRunner taskRunner,
            TaskExecutionLifecycle lifecycle) {
        Mono<TaskExecutionResult> existing = executions.get(task.taskId());
        if (existing != null) {
            return existing;
        }

        // 当前任务在开始执行之前必须等待其完成的任务 id 列表
        List<String> requiredTaskIds = requiredTaskIds(task, readTaskIds);
        // 把依赖 id 列表转成一个响应式流，逐个元素（即每个依赖 id）向下游推送。
        Mono<List<TaskExecutionResult>> dependencyResults = Flux.fromIterable(requiredTaskIds)
                .concatMap(taskId -> {
                    // 根据id获取依赖任务
                    PlannedTask dependency = taskById.get(taskId);
                    if (dependency == null) {
                        return Mono.error(new IllegalArgumentException("任务依赖不存在: " + taskId));
                    }
                    // 开始递归，依赖任务进行递归
                    return createExecution(
                            dependency, taskById, readTaskIds, executions, taskRunner, lifecycle);
                })
                .collectList();
        // 我依赖的这堆任务全部跑完,把它们的结果收集成一个 List
        Mono<TaskExecutionResult> execution = dependencyResults.flatMap(results -> {
            // 判断依赖的任务是否正常执行，如果正常执行就调用固定链路，如果不正常就阻塞
            Mono<TaskExecutionResult> recoveredExecution = executeTask(task, results, taskRunner)
                    .onErrorResume(exception -> recoverTaskFailure(task, exception));
            // 依赖完成后再领取任务，避免把尚未具备执行条件的任务提前标记为运行中。
            return lifecycle.execute(task, () -> recoveredExecution);
        });
        // 每个缓存任务只记录一次终态和耗时，依赖订阅与最终汇总不会重复增加指标。
        execution = chatMetrics.observeTask(execution, task.intent()).cache();
        executions.put(task.taskId(), execution);
        return execution;
    }

    /**
     * 将单个任务异常转换为稳定失败结果，使其他无依赖任务可以继续执行。
     *
     * @param task 当前任务
     * @param exception 原始执行异常
     * @return 当前任务的失败终态
     */
    private Mono<TaskExecutionResult> recoverTaskFailure(
            PlannedTask task,
            Throwable exception) {
        // 日志只记录稳定任务元数据和异常类型，不输出用户问题或工具响应正文。
        LOGGER.warn(
                "Agent子任务执行失败，taskId={}, sequence={}, intent={}, exception={}",
                task.taskId(),
                task.sequence(),
                task.intent(),
                exception.getClass().getSimpleName());
        TaskExecutionResult failed = TaskExecutionResult.failed(
                task.taskId(),
                task.sequence(),
                task.intent(),
                task.standaloneQuestion());
        return Mono.just(failed);
    }

    /**
     * 合并模型声明依赖和交易任务的只读屏障。
     *
     * @param task 当前待调度任务
     * @param readTaskIds 当前计划中的全部只读任务
     * @return 当前任务开始前必须完成的任务标识
     */
    private List<String> requiredTaskIds(
            PlannedTask task,
            List<String> readTaskIds) {
        // 这里是当前任务的依赖
        LinkedHashSet<String> required = new LinkedHashSet<>(task.dependsOn());
        // 判断当前意图是否命中了交易类的意图
        if (isTransaction(task.intent())) {
            // 如果是交易类的任务需要等待所有的查询类的任务
            readTaskIds.stream()
                    .filter(taskId -> !task.taskId().equals(taskId))
                    .forEach(required::add);
        }
        return List.copyOf(required);
    }

    /**
     * 根据依赖终态决定执行当前任务还是直接标记阻塞。
     *
     * @param task 当前待执行任务
     * @param dependencyResults 当前任务需要等待的结果
     * @param taskRunner 实际业务执行函数
     * @return 当前任务的异步终态
     */
    private Mono<TaskExecutionResult> executeTask(
            PlannedTask task,
            List<TaskExecutionResult> dependencyResults,
            TaskRunner taskRunner) {
        boolean dependencyFailed = dependencyResults.stream()
                .anyMatch(result -> result.status() != TaskExecutionStatus.SUCCESS);
        if (dependencyFailed && !isTransaction(task.intent())) {
            // 普通依赖任务不能使用失败或待补充结果继续推导业务结论。
            return Mono.just(TaskExecutionResult.blocked(
                    task.taskId(),
                    task.sequence(),
                    task.intent(),
                    task.standaloneQuestion()));
        }

        // 交易任务的只读屏障包含所有查询，其中只有模型显式 dependsOn 的失败才会阻塞交易。
        List<TaskExecutionResult> declaredDependencies = new ArrayList<>();
        for (TaskExecutionResult result : dependencyResults) {
            //如果等待任务是当前任务的依赖就加入list
            if (task.dependsOn().contains(result.taskId())) {
                declaredDependencies.add(result);
            }
        }
        // 判断依赖任务里面是否有没有成功的
        boolean declaredDependencyFailed = declaredDependencies.stream()
                .anyMatch(result -> result.status() != TaskExecutionStatus.SUCCESS);
        // 如果有未成功的就阻塞
        if (declaredDependencyFailed) {
            return Mono.just(TaskExecutionResult.blocked(
                    task.taskId(),
                    task.sequence(),
                    task.intent(),
                    task.standaloneQuestion()));
        }
        // 如果检验通过，开始执行taskRunner.execute。开始执行固定链路
        Mono<TaskExecutionResult> execution = taskRunner.execute(task, List.copyOf(declaredDependencies));
        if (isTransaction(task.intent())) {
            // 同步交易链即使取消订阅也可能继续改变草案状态，不能用响应式超时制造“已停止”的假象。
            return execution;
        }
        // 只读任务超过独立时限后释放当前计划的等待关系，其他无依赖任务仍可正常完成。
        Mono<TaskExecutionResult> timedExecution = execution
                .timeout(properties.readTaskTimeout())
                .onErrorResume(TimeoutException.class, exception -> recoverTaskTimeout(task));
        return timedExecution;
    }

    /**
     * 将只读任务超时转换为稳定超时结果。
     *
     * @param task 当前只读任务
     * @return 当前任务的超时终态
     */
    private Mono<TaskExecutionResult> recoverTaskTimeout(PlannedTask task) {
        // 超时仅终止当前只读任务，不阻断其他无依赖任务。
        LOGGER.warn(
                "Agent只读子任务执行超时，taskId={}, sequence={}, intent={}",
                task.taskId(),
                task.sequence(),
                task.intent());
        TaskExecutionResult timedOut = TaskExecutionResult.timedOut(
                task.taskId(),
                task.sequence(),
                task.intent(),
                task.standaloneQuestion());
        return Mono.just(timedOut);
    }

    /**
     * 判断当前意图是否会创建或变更业务状态。
     *
     * @param intent 当前任务意图
     * @return 是否必须通过固定代码链串行执行
     */
    private boolean isTransaction(AgentIntent intent) {
        return TRANSACTION_INTENTS.contains(intent);
    }

    /**
     * 单个任务的实际业务执行边界。
     */
    @FunctionalInterface
    public interface TaskRunner {

        /**
         * 执行一个已满足依赖条件的子任务。
         *
         * @param task 当前子任务
         * @param dependencyResults 模型显式声明的前置结果
         * @return 当前任务的异步执行结果
         */
        Mono<TaskExecutionResult> execute(
                PlannedTask task,
                List<TaskExecutionResult> dependencyResults);
    }

    /**
     * 单个任务的领取、检查点复用和终态提交生命周期。
     */
    @FunctionalInterface
    public interface TaskExecutionLifecycle {

        /**
         * 包装已经具备稳定结果语义的单任务执行流。
         *
         * @param task 当前服务端任务
         * @param execution 实际执行函数
         * @return 复用或新提交的任务结果
         */
        Mono<TaskExecutionResult> execute(
                PlannedTask task,
                Supplier<Mono<TaskExecutionResult>> execution);
    }
}
