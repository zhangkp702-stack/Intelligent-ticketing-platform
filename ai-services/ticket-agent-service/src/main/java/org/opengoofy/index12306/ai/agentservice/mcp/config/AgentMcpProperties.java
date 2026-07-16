package org.opengoofy.index12306.ai.agentservice.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 调用内部票务 MCP 服务所需的签名配置。
 *
 * @param internalSecret Agent 与 MCP 服务共享的内部密钥
 */
@ConfigurationProperties(prefix = "index12306.agent.mcp")
public record AgentMcpProperties(@DefaultValue("") String internalSecret) {
}
