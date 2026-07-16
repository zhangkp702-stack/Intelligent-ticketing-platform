package org.opengoofy.index12306.ai.agentservice.action.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 高风险操作确认令牌与执行边界配置。
 *
 * @param confirmationSecret 确认令牌 HMAC 密钥
 * @param confirmationTtl 购票草案允许确认的时间窗口
 */
@ConfigurationProperties(prefix = "index12306.agent.action")
public record AgentActionProperties(
        @DefaultValue("") String confirmationSecret,
        @DefaultValue("5m") Duration confirmationTtl) {
}
