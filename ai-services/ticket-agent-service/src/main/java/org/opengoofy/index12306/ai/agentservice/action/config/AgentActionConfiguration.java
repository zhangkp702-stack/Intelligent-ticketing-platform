package org.opengoofy.index12306.ai.agentservice.action.config;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

/**
 * 启用高风险操作状态机并在启动时校验确认密钥。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        AgentActionProperties.class,
        ActionReconciliationProperties.class,
        ActionManualReviewProperties.class})
public class AgentActionConfiguration {

    /**
     * 校验确认令牌密钥和有效期，禁止安全配置缺失时降级启动。
     *
     * @param properties 高风险操作配置
     * @param reconciliationProperties UNKNOWN 操作对账配置
     * @param manualReviewProperties 人工只读对账入口配置
     */
    public AgentActionConfiguration(
            AgentActionProperties properties,
            ActionReconciliationProperties reconciliationProperties,
            ActionManualReviewProperties manualReviewProperties) {
        // 确认令牌保护真实下单入口，必须使用独立或与 MCP 相同强度的外部密钥。
        Assert.hasText(properties.confirmationSecret(),
                "TICKET_AGENT_CONFIRMATION_SECRET must be configured");
        Assert.isTrue(properties.confirmationSecret().length() >= 32,
                "TICKET_AGENT_CONFIRMATION_SECRET must contain at least 32 characters");
        Assert.isTrue(!properties.confirmationTtl().isNegative() && !properties.confirmationTtl().isZero(),
                "confirmation TTL must be positive");
        // 对账次数和时间窗口必须为正，防止任务立即失效或形成无界热循环。
        Assert.isTrue(reconciliationProperties.maxAttempts() > 0,
                "action reconciliation max attempts must be positive");
        Assert.isTrue(!reconciliationProperties.retryDelay().isNegative()
                        && !reconciliationProperties.retryDelay().isZero(),
                "action reconciliation retry delay must be positive");
        Assert.isTrue(!reconciliationProperties.leaseDuration().isNegative()
                        && !reconciliationProperties.leaseDuration().isZero(),
                "action reconciliation lease duration must be positive");
        if (manualReviewProperties.enabled()) {
            // 人工入口必须显式启用并配置独立高强度密钥，避免管理路由误暴露后可被任意重试。
            Assert.hasText(manualReviewProperties.secret(),
                    "manual review secret must be configured when manual review is enabled");
            Assert.isTrue(manualReviewProperties.secret().length() >= 32,
                    "manual review secret must contain at least 32 characters");
        }
    }
}
