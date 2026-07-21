package org.opengoofy.index12306.ai.agentservice.infra.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用智能体模型路由配置绑定。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentModelProperties.class)
public class AgentModelConfiguration {
}
