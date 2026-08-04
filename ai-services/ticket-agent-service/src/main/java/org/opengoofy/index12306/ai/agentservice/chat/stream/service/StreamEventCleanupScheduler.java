package org.opengoofy.index12306.ai.agentservice.chat.stream.service;

import org.opengoofy.index12306.ai.agentservice.chat.config.AgentStreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 分批清理已经超过保留期的终态 SSE 事件，控制事件表长期容量。
 */
@Service
@ConditionalOnProperty(
        prefix = "index12306.agent.stream",
        name = "cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class StreamEventCleanupScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamEventCleanupScheduler.class);

    private final DurableStreamEventService durableStreamEventService;
    private final AgentStreamProperties properties;
    private final Clock clock;

    /**
     * 创建终态流事件清理调度器。
     *
     * @param durableStreamEventService 事件清理事务服务
     * @param properties 事件保留和批次配置
     * @param clock 统一时间源
     */
    public StreamEventCleanupScheduler(
            DurableStreamEventService durableStreamEventService,
            AgentStreamProperties properties,
            Clock clock) {
        this.durableStreamEventService = durableStreamEventService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 周期清理一批过期终态事件；运行中轮次和 Turn 序号水位不受影响。
     */
    @Scheduled(
            initialDelayString = "${index12306.agent.stream.cleanup-interval-millis:60000}",
            fixedDelayString = "${index12306.agent.stream.cleanup-interval-millis:60000}")
    public void cleanupExpiredEvents() {
        // 每次只删除一个有界批次，避免清理任务长时间占用数据库连接和事务锁。
        Instant cutoff = clock.instant().minus(properties.eventRetention());
        int deleted = durableStreamEventService.cleanupTerminalEventsBefore(
                cutoff, properties.cleanupBatchSize());
        if (deleted > 0) {
            LOGGER.info("清理过期Agent SSE事件完成，deleted={}, cutoff={}", deleted, cutoff);
        }
    }
}
