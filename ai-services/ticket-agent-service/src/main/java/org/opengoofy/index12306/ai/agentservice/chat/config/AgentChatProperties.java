package org.opengoofy.index12306.ai.agentservice.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 智能体在线对话的超时和任务调度配置。
 *
 * @param responseTimeout 一轮对话从接收到完成允许占用的最长时间
 * @param readTaskTimeout 单个只读固定链允许占用的最长时间
 * @param taskMaxConcurrency 单轮任务计划允许并行执行的最大任务数
 */
@ConfigurationProperties(prefix = "index12306.agent.chat")
public record AgentChatProperties(
        @DefaultValue("60s") Duration responseTimeout,
        @DefaultValue("25s") Duration readTaskTimeout,
        @DefaultValue("4") int taskMaxConcurrency) {

    /**
     * 校验在线对话和任务调度参数，防止错误配置在运行时关闭超时或并发边界。
     */
    public AgentChatProperties {
        // 所有持续时间必须为正数，零值会让正常请求在订阅后立即超时。
        if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) {
            throw new IllegalArgumentException("responseTimeout 必须大于 0");
        }
        if (readTaskTimeout == null || readTaskTimeout.isZero() || readTaskTimeout.isNegative()) {
            throw new IllegalArgumentException("readTaskTimeout 必须大于 0");
        }
        // 并发数限制在任务规划器允许的最大任务数量以内，避免配置绕过单轮资源边界。
        if (taskMaxConcurrency < 1 || taskMaxConcurrency > 8) {
            throw new IllegalArgumentException("taskMaxConcurrency 必须在 1 到 8 之间");
        }
    }
}
