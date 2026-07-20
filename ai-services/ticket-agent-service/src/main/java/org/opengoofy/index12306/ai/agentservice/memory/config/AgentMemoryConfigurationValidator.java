package org.opengoofy.index12306.ai.agentservice.memory.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 在应用启动时校验会话上下文和异步摘要配置边界。
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
     * 校验上下文容量、摘要阈值、重试次数和任务租约。
     */
    @Override
    public void afterSingletonsInstantiated() {
        // 上下文数量和 Token 预算必须为正，避免生成缺少当前问题的模型输入。
        Assert.isTrue(properties.recentTurnLimit() > 0, "最近完整轮次数量必须大于零");
        Assert.isTrue(properties.contextTokenBudget() > 0, "上下文 Token 预算必须大于零");

        // 摘要阈值和有限重试共同约束后台任务量，防止失败任务无限循环。
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
    }
}
