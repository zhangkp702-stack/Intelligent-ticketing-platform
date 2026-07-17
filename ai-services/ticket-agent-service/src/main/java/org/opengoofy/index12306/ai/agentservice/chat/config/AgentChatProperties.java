package org.opengoofy.index12306.ai.agentservice.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 智能体在线对话的超时配置。
 *
 * @param responseTimeout 一轮对话从接收到完成允许占用的最长时间
 */
@ConfigurationProperties(prefix = "index12306.agent.chat")
public record AgentChatProperties(
        @DefaultValue("60s") Duration responseTimeout) {
}
