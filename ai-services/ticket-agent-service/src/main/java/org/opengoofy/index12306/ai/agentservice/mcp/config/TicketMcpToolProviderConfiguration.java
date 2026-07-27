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
import java.util.function.Supplier;

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
        // MCP 客户端 Bean 在容器启动后才完成初始化，客户端集合和工具清单都必须在实际使用时读取。
        return auditedProvider(
                () -> {
                    List<McpSyncClient> clients = clientLists.stream().flatMap(List::stream).toList();
                    return SyncMcpToolCallbackProvider.builder()
                            .mcpClients(clients)
                            .toolFilter(toolFilter)
                            .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                            .toolContextToMcpMetaConverter(metadataConverter)
                            .build();
                },
                auditService,
                objectMapper);
    }

    /**
     * 创建按调用时工具清单动态添加审计包装的提供器。
     *
     * @param delegateSupplier 原始 MCP 工具提供器工厂
     * @param auditService 工具调用审计服务
     * @param objectMapper JSON 解析器
     * @return 不缓存启动阶段工具清单的审计工具提供器
     */
    static ToolCallbackProvider auditedProvider(
            Supplier<ToolCallbackProvider> delegateSupplier,
            ToolCallAuditService auditService,
            ObjectMapper objectMapper) {
        // 每次重新取得 MCP 客户端及其最新工具清单，使延迟初始化后的工具能够进入回答流水线。
        return () -> Arrays.stream(delegateSupplier.get().getToolCallbacks())
                .map(callback -> new AuditedToolCallback(callback, auditService, objectMapper))
                .toArray(ToolCallback[]::new);
    }
}
