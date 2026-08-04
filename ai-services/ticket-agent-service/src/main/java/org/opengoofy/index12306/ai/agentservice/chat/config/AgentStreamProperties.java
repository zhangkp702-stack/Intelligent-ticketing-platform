package org.opengoofy.index12306.ai.agentservice.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 持久化 SSE 事件的保留窗口和单批清理上限。
 *
 * @param eventRetention 终态轮次流事件保留时长
 * @param cleanupBatchSize 单次调度最大删除数量
 */
@ConfigurationProperties(prefix = "index12306.agent.stream")
public record AgentStreamProperties(
        @DefaultValue("24h") Duration eventRetention,
        @DefaultValue("1000") int cleanupBatchSize) {

    /**
     * 校验流事件保留与清理配置，避免立即过期或无界批处理。
     */
    public AgentStreamProperties {
        // 保留期和批次都必须为正，清理任务才能保持可预测的资源边界。
        if (eventRetention == null || eventRetention.isZero() || eventRetention.isNegative()) {
            throw new IllegalArgumentException("eventRetention 必须大于 0");
        }
        if (cleanupBatchSize <= 0) {
            throw new IllegalArgumentException("cleanupBatchSize 必须大于 0");
        }
    }
}
