package org.opengoofy.index12306.ai.agentservice.chat.stream;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentStreamProperties;
import org.opengoofy.index12306.ai.agentservice.chat.stream.service.DurableStreamEventService;
import org.opengoofy.index12306.ai.agentservice.chat.stream.service.StreamEventCleanupScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 验证流事件定时清理使用配置的保留窗口和单批上限。
 */
class StreamEventCleanupSchedulerTests {

    /**
     * 验证调度器基于统一时间源计算截止时间，并且每次只发起一个有界清理批次。
     */
    @Test
    void cleanupUsesConfiguredRetentionAndBatchSize() {
        DurableStreamEventService streamEventService = mock(DurableStreamEventService.class);
        Instant now = Instant.parse("2026-08-03T08:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AgentStreamProperties properties = new AgentStreamProperties(Duration.ofHours(24), 1000);
        StreamEventCleanupScheduler scheduler = new StreamEventCleanupScheduler(
                streamEventService, properties, clock);

        // 调度入口只负责确定本批边界，终态过滤和事务删除由持久化服务完成。
        scheduler.cleanupExpiredEvents();

        verify(streamEventService).cleanupTerminalEventsBefore(
                now.minus(Duration.ofHours(24)), 1000);
    }
}
