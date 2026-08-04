package org.opengoofy.index12306.ai.agentservice.chat.execution.service;

import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Supplier;

/**
 * 把响应式单任务执行包装为“领取检查点、执行、fenced 提交”的持久化生命周期。
 */
@Service
public class DurableTaskExecutionCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DurableTaskExecutionCoordinator.class);

    private final TaskExecutionCheckpointService checkpointService;

    /**
     * 创建持久化任务执行协调器。
     *
     * @param checkpointService 任务检查点状态服务
     */
    public DurableTaskExecutionCoordinator(TaskExecutionCheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    /**
     * 复用终态任务，或在当前 Turn 执行权下领取并提交新结果。
     *
     * @param context 当前执行上下文
     * @param task 当前服务端任务
     * @param execution 已包含超时和安全失败收口的实际执行函数
     * @return 可供依赖任务消费的持久化结果
     */
    public Mono<TaskExecutionResult> execute(
            AgentRequestContext context,
            PlannedTask task,
            Supplier<Mono<TaskExecutionResult>> execution) {
        return Mono.fromCallable(() -> checkpointService.claim(context, task))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(claim -> {
                    if (!claim.execute()) {
                        // 已完成检查点直接返回，恢复执行不会再次进入固定查询链或交易草案链。
                        return Mono.just(claim.existingResult());
                    }
                    Mono<TaskExecutionResult> actual = execution.get();
                    if (actual == null) {
                        return Mono.error(new IllegalStateException("任务执行器返回空结果流"));
                    }
                    return actual
                            .flatMap(result -> Mono.fromCallable(() -> checkpointService.complete(
                                            context, task, result))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    // 数据库保存去除临时令牌的副本，本次在线响应仍沿用当前权威视图。
                                    .thenReturn(result))
                            .doOnCancel(() -> cancelClaim(context, task));
                });
    }

    /**
     * 在订阅取消时尽力收口当前任务，迟到或已接管的 token 保持无副作用。
     *
     * @param context 当前执行上下文
     * @param task 当前服务端任务
     */
    private void cancelClaim(
            AgentRequestContext context,
            PlannedTask task) {
        try {
            // 取消回调不能等待另一个调度周期，否则任务会在租约到期前长期显示 RUNNING。
            checkpointService.cancel(context, task);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Agent任务取消检查点写入失败，turnId={}, taskId={}, exceptionType={}",
                    context.turnId(), task.taskId(), exception.getClass().getSimpleName());
        }
    }
}
