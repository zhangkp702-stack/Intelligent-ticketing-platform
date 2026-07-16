package org.opengoofy.index12306.ai.agentservice.action.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

/**
 * 启用高风险操作状态机并在启动时校验确认密钥。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentActionProperties.class)
public class AgentActionConfiguration {

    /**
     * 校验确认令牌密钥和有效期，禁止安全配置缺失时降级启动。
     *
     * @param properties 高风险操作配置
     */
    public AgentActionConfiguration(AgentActionProperties properties) {
        // 确认令牌保护真实下单入口，必须使用独立或与 MCP 相同强度的外部密钥。
        Assert.hasText(properties.confirmationSecret(),
                "TICKET_AGENT_CONFIRMATION_SECRET must be configured");
        Assert.isTrue(properties.confirmationSecret().length() >= 32,
                "TICKET_AGENT_CONFIRMATION_SECRET must contain at least 32 characters");
        Assert.isTrue(!properties.confirmationTtl().isNegative() && !properties.confirmationTtl().isZero(),
                "confirmation TTL must be positive");
    }
}
