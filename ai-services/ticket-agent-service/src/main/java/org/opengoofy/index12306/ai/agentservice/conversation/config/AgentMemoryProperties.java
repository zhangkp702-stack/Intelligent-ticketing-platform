package org.opengoofy.index12306.ai.agentservice.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 会话上下文与异步摘要任务配置。
 *
 * @param recentTurnLimit 会话最多加载的未压缩完整轮次数
 * @param contextTokenBudget 单次会话上下文允许的估算 Token 上限
 * @param summaryTriggerMessageCount 触发新摘要所需的未压缩消息数
 * @param summaryMaxAttempts 摘要任务最大尝试次数
 * @param summaryRetryDelay 摘要失败后的基础重试等待时间
 * @param summaryLeaseDuration MQ 消费者持有任务租约的时间
 */
@ConfigurationProperties(prefix = "index12306.agent.memory")
public record AgentMemoryProperties(
        @DefaultValue("6") int recentTurnLimit,
        @DefaultValue("12000") int contextTokenBudget,
        @DefaultValue("12") int summaryTriggerMessageCount,
        @DefaultValue("3") int summaryMaxAttempts,
        @DefaultValue("30s") Duration summaryRetryDelay,
        @DefaultValue("2m") Duration summaryLeaseDuration) {
}
