package org.opengoofy.index12306.ai.agentservice.memory.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证摘要异步调度结果使用低基数指标记录。
 */
class SummaryTaskDispatcherMetricsTests {

    /**
     * 验证摘要处理器不可用时记录跳过结果且不领取数据库任务。
     */
    @Test
    @SuppressWarnings("unchecked")
    void recordsSkippedTaskWhenProcessorIsUnavailable() {
        SummaryTaskService taskService = mock(SummaryTaskService.class);
        ObjectProvider<SummaryTaskProcessor> processorProvider = mock(ObjectProvider.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(processorProvider.getIfAvailable()).thenReturn(null);
        SummaryTaskDispatcher dispatcher = new SummaryTaskDispatcher(
                taskService, processorProvider, meterRegistry);

        // 没有处理器时任务保持待处理，指标记录跳过而不触碰任务状态机。
        assertThat(dispatcher.dispatch("task-1").join()).isFalse();
        assertThat(meterRegistry.get("agent.summary.tasks")
                .tag("outcome", "SKIPPED")
                .counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("agent.summary.task.duration")
                .tag("outcome", "SKIPPED")
                .timer().count()).isEqualTo(1);
        verifyNoInteractions(taskService);
    }
}
