package org.opengoofy.index12306.ai.agentservice.mcp.config;

import org.opengoofy.index12306.ai.agentservice.mcp.security.SignedMcpMetadataConverter;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 配置票务 MCP 客户端的签名元数据和只读工具白名单。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentMcpProperties.class)
public class AgentMcpConfiguration {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "resolve_station",
            "query_tickets",
            "query_train_stops",
            "list_my_passengers",
            "list_my_orders",
            "get_my_order_detail",
            "preview_order_cancellation",
            "preview_ticket_refund",
            "query_pay_status");

    /**
     * 在 MCP 客户端启用时注册身份签名转换器。
     *
     * @param properties MCP 内部密钥配置
     * @return 工具上下文到签名 MCP 元数据的转换器
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true")
    public ToolContextToMcpMetaConverter signedMcpMetadataConverter(AgentMcpProperties properties) {
        // 签名器确保身份元数据独立于模型可控参数传输。
        return new SignedMcpMetadataConverter(properties);
    }

    /**
     * 仅允许显式白名单中的只读查询和预览工具进入模型工具集合。
     *
     * @return MCP 工具发现过滤器
     */
    @Bean
    public McpToolFilter readOnlyTicketMcpToolFilter() {
        // 即使 MCP 服务未来新增写工具，Agent 也不会自动获得调用权限。
        return (connectionInfo, tool) -> READ_ONLY_TOOLS.contains(tool.name());
    }
}
