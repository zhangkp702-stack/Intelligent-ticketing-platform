package org.opengoofy.index12306.ai.agentservice.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 会话上下文、摘要触发和专用线程池配置。
 *
 * @param topicCandidateLimit 主题路由最多加载的摘要卡片数
 * @param recentUserQuestionLimit 主题路由最多加载的最近用户问题数
 * @param recentMessageLimit 选定主题后最多加载的未压缩消息数
 * @param contextTokenBudget 单次主题上下文允许的估算 Token 上限
 * @param summaryTriggerMessageCount 触发新摘要所需的未压缩消息数
 * @param summaryMaxAttempts 摘要任务最大尝试次数
 * @param summaryRetryDelay 摘要失败后的基础重试等待时间
 * @param summaryLeaseDuration 摘要执行器持有任务租约的时间
 * @param summaryExecutor 摘要专用线程池配置
 */
@ConfigurationProperties(prefix = "index12306.agent.memory")
public record AgentMemoryProperties(
        @DefaultValue("6") int topicCandidateLimit,
        @DefaultValue("6") int recentUserQuestionLimit,
        @DefaultValue("16") int recentMessageLimit,
        @DefaultValue("12000") int contextTokenBudget,
        @DefaultValue("12") int summaryTriggerMessageCount,
        @DefaultValue("3") int summaryMaxAttempts,
        @DefaultValue("30s") Duration summaryRetryDelay,
        @DefaultValue("2m") Duration summaryLeaseDuration,
        SummaryExecutor summaryExecutor) {

    /**
     * 摘要任务专用线程池参数。
     *
     * @param corePoolSize 核心线程数
     * @param maxPoolSize 最大线程数
     * @param queueCapacity 等待队列容量
     * @param awaitTermination 关闭应用时等待任务结束的时间
     */
    public record SummaryExecutor(
            @DefaultValue("2") int corePoolSize,
            @DefaultValue("4") int maxPoolSize,
            @DefaultValue("200") int queueCapacity,
            @DefaultValue("20s") Duration awaitTermination) {
    }
}
