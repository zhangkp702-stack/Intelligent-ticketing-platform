package org.opengoofy.index12306.ai.agentservice.memory.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 在摘要专用线程池中驱动任务领取、模型处理和状态提交。
 */
@Component
public class SummaryTaskDispatcher {

    private final SummaryTaskService taskService;
    private final ObjectProvider<SummaryTaskProcessor> processorProvider;
    private final MeterRegistry meterRegistry;

    /**
     * 创建异步摘要任务调度器。
     *
     * @param taskService 摘要任务状态服务
     * @param processorProvider 阶段四提供的摘要模型处理器
     * @param meterRegistry 摘要任务指标注册表
     */
    public SummaryTaskDispatcher(
            SummaryTaskService taskService,
            ObjectProvider<SummaryTaskProcessor> processorProvider,
            MeterRegistry meterRegistry) {
        this.taskService = taskService;
        this.processorProvider = processorProvider;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 异步执行指定摘要任务；处理器尚未注册时保持任务待执行状态。
     *
     * @param taskId 摘要任务标识
     * @return 是否成功完成摘要处理
     */
    @Async("agentSummaryExecutor")
    public CompletableFuture<Boolean> dispatch(String taskId) {
        long startedNanos = System.nanoTime();
        SummaryTaskProcessor processor = processorProvider.getIfAvailable();
        if (processor == null) {
            // 阶段三不伪造摘要结果，等待阶段四注册真实模型处理器后再领取任务。
            recordResult("SKIPPED", startedNanos);
            return CompletableFuture.completedFuture(false);
        }

        String workerId = "summary-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            // 数据库锁只用于短暂领取，耗时模型调用在线程池中且不持有事务锁。
            SummaryTaskService.SummaryWorkItem workItem = taskService.claim(taskId, workerId);
            SummaryTaskService.SummaryGenerationResult result = processor.process(workItem);
            taskService.complete(taskId, result);
            recordResult("SUCCESS", startedNanos);
            return CompletableFuture.completedFuture(true);
        } catch (Exception ex) {
            // 只保存异常类型和简短说明，任务状态机决定后续重试或最终失败。
            try {
                taskService.fail(taskId, ex.getClass().getSimpleName(), ex.getMessage());
            } catch (RuntimeException ignored) {
                // 领取前失败或任务已由其他实例接管时，不覆盖原任务状态。
            }
            recordResult("FAILURE", startedNanos);
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * 记录摘要任务执行结果和从异步线程开始处理到终态提交的耗时。
     *
     * @param outcome 摘要任务结果
     * @param startedNanos 异步处理开始时间
     */
    private void recordResult(String outcome, long startedNanos) {
        // 任务标识不进入标签，避免摘要任务数量增长导致指标基数失控。
        meterRegistry.counter("agent.summary.tasks", "outcome", outcome).increment();
        Timer.builder("agent.summary.task.duration")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)));
    }
}
