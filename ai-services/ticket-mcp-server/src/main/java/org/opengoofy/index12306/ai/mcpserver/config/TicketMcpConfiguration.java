package org.opengoofy.index12306.ai.mcpserver.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用票务 MCP 内部鉴权、下游服务地址和结果边界配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TicketMcpProperties.class)
public class TicketMcpConfiguration {
}
