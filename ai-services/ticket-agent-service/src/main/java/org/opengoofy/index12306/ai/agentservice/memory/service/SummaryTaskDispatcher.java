package org.opengoofy.index12306.ai.agentservice.memory.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 在摘要专用线程池中驱动任务领取、模型处理和状态提交。
 */
@Component
public class SummaryTaskDispatcher {

    private final SummaryTaskService taskService;
    private final ObjectProvider<SummaryTaskProcessor> processorProvider;

    /**
     * 创建异步摘要任务调度器。
     *
     * @param taskService 摘要任务状态服务
     * @param processorProvider 阶段四提供的摘要模型处理器
     */
    public SummaryTaskDispatcher(
            SummaryTaskService taskService,
            ObjectProvider<SummaryTaskProcessor> processorProvider) {
        this.taskService = taskService;
        this.processorProvider = processorProvider;
    }

    /**
     * 异步执行指定摘要任务；处理器尚未注册时保持任务待执行状态。
     *
     * @param taskId 摘要任务标识
     * @return 是否成功完成摘要处理
     */
    @Async("agentSummaryExecutor")
    public CompletableFuture<Boolean> dispatch(String taskId) {
        SummaryTaskProcessor processor = processorProvider.getIfAvailable();
        if (processor == null) {
            // 阶段三不伪造摘要结果，等待阶段四注册真实模型处理器后再领取任务。
            return CompletableFuture.completedFuture(false);
        }

        String workerId = "summary-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            // 数据库锁只用于短暂领取，耗时模型调用在线程池中且不持有事务锁。
            SummaryTaskService.SummaryWorkItem workItem = taskService.claim(taskId, workerId);
            SummaryTaskService.SummaryGenerationResult result = processor.process(workItem);
            taskService.complete(taskId, result);
            return CompletableFuture.completedFuture(true);
        } catch (Exception ex) {
            // 只保存异常类型和简短说明，任务状态机决定后续重试或最终失败。
            try {
                taskService.fail(taskId, ex.getClass().getSimpleName(), ex.getMessage());
            } catch (RuntimeException ignored) {
                // 领取前失败或任务已由其他实例接管时，不覆盖原任务状态。
            }
            return CompletableFuture.completedFuture(false);
        }
    }
}
