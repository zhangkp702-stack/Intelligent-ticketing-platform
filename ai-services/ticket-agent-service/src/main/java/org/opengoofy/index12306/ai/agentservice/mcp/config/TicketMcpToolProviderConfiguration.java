package org.opengoofy.index12306.ai.agentservice.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.opengoofy.index12306.ai.agentservice.mcp.audit.AuditedToolCallback;
import org.opengoofy.index12306.ai.agentservice.mcp.audit.ToolCallAuditService;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 将只读 MCP 工具包装为带持久化审计的 Spring AI 工具回调集合。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true")
public class TicketMcpToolProviderConfiguration {

    /**
     * 从已连接的 MCP 客户端发现白名单工具，并为每个工具添加审计装饰器。
     *
     * @param clientLists Spring AI 创建的同步 MCP 客户端集合
     * @param toolFilter 只读工具白名单过滤器
     * @param metadataConverter 身份签名元数据转换器
     * @param auditService 工具调用审计服务
     * @param objectMapper JSON 解析器
     * @return 可供 ChatClient 使用的审计工具提供器
     */
    @Bean
    public ToolCallbackProvider ticketMcpToolCallbacks(
            ObjectProvider<List<McpSyncClient>> clientLists,
            McpToolFilter toolFilter,
            ToolContextToMcpMetaConverter metadataConverter,
            ToolCallAuditService auditService,
            ObjectMapper objectMapper) {
        // Spring AI 可能按传输配置创建多组客户端，先合并后统一发现工具。
        List<McpSyncClient> clients = clientLists.stream().flatMap(List::stream).toList();
        SyncMcpToolCallbackProvider delegate = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .toolFilter(toolFilter)
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .toolContextToMcpMetaConverter(metadataConverter)
                .build();

        // 模型只能获得经过审计包装后的工具回调，原始回调不注册到容器。
        ToolCallback[] callbacks = Arrays.stream(delegate.getToolCallbacks())
                .map(callback -> new AuditedToolCallback(callback, auditService, objectMapper))
                .toArray(ToolCallback[]::new);
        return ToolCallbackProvider.from(callbacks);
    }
}
