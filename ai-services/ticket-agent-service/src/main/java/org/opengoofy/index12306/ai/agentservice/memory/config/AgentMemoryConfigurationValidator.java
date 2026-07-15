package org.opengoofy.index12306.ai.agentservice.memory.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 在应用启动阶段阻止无效的上下文和摘要线程池参数进入运行状态。
 */
@Component
public class AgentMemoryConfigurationValidator implements SmartInitializingSingleton {

    private final AgentMemoryProperties properties;

    /**
     * 创建记忆配置校验器。
     *
     * @param properties 待校验的记忆配置
     */
    public AgentMemoryConfigurationValidator(AgentMemoryProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验上下文容量、摘要重试和线程池边界。
     */
    @Override
    public void afterSingletonsInstantiated() {
        // 上下文候选和 Token 预算必须为正，避免路由输入或最终上下文为空。
        Assert.isTrue(properties.topicCandidateLimit() > 0, "主题候选数量必须大于零");
        Assert.isTrue(properties.recentUserQuestionLimit() > 0, "最近用户问题数量必须大于零");
        Assert.isTrue(properties.recentMessageLimit() > 0, "最近消息数量必须大于零");
        Assert.isTrue(properties.contextTokenBudget() > 0, "上下文 Token 预算必须大于零");
        Assert.isTrue(properties.summaryTriggerMessageCount() > 0, "摘要触发消息数必须大于零");
        Assert.isTrue(properties.summaryMaxAttempts() > 0, "摘要最大尝试次数必须大于零");
        Assert.isTrue(properties.summaryRetryDelay() != null
                        && !properties.summaryRetryDelay().isNegative()
                        && !properties.summaryRetryDelay().isZero(),
                "摘要重试等待时间必须大于零");
        Assert.isTrue(properties.summaryLeaseDuration() != null
                        && !properties.summaryLeaseDuration().isNegative()
                        && !properties.summaryLeaseDuration().isZero(),
                "摘要任务租约时间必须大于零");

        // 专用线程池最大线程数不得小于核心线程数，队列必须能够承接异步任务。
        AgentMemoryProperties.SummaryExecutor executor = properties.summaryExecutor();
        Assert.notNull(executor, "摘要线程池配置不能为空");
        Assert.isTrue(executor.corePoolSize() > 0, "摘要核心线程数必须大于零");
        Assert.isTrue(executor.maxPoolSize() >= executor.corePoolSize(),
                "摘要最大线程数不能小于核心线程数");
        Assert.isTrue(executor.queueCapacity() > 0, "摘要队列容量必须大于零");
        Assert.isTrue(executor.awaitTermination() != null
                        && !executor.awaitTermination().isNegative(),
                "摘要关闭等待时间不能为负数");
    }
}
