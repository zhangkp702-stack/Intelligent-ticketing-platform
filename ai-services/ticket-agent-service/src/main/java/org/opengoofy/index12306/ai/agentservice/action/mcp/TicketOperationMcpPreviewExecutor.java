package org.opengoofy.index12306.ai.agentservice.action.mcp;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.CancellationPreview;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.RefundPreview;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.audit.AuditedToolCallback;
import org.opengoofy.index12306.ai.agentservice.mcp.audit.ToolCallAuditService;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通过独立 MCP 回调读取取消和退票预览，避免信任模型生成的状态与金额。
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true")
public class TicketOperationMcpPreviewExecutor implements TicketOperationPreviewExecutor {

    private static final String CANCELLATION_PREVIEW_TOOL = "preview_order_cancellation";
    private static final String REFUND_PREVIEW_TOOL = "preview_ticket_refund";
    private static final Set<String> PREVIEW_TOOLS = Set.of(
            CANCELLATION_PREVIEW_TOOL, REFUND_PREVIEW_TOOL);

    private final Map<String, ToolCallback> callbacks;
    private final ObjectMapper objectMapper;
    private final McpToolContextFactory contextFactory;

    /**
     * 创建仅发现两个订单操作预览工具的可信执行器。
     *
     * @param clientLists Spring AI 创建的同步 MCP 客户端集合
     * @param metadataConverter 工具上下文签名转换器
     * @param auditService MCP 工具调用审计服务
     * @param objectMapper JSON 序列化器
     * @param contextFactory MCP 工具上下文工厂
     */
    public TicketOperationMcpPreviewExecutor(
            ObjectProvider<List<McpSyncClient>> clientLists,
            ToolContextToMcpMetaConverter metadataConverter,
            ToolCallAuditService auditService,
            ObjectMapper objectMapper,
            McpToolContextFactory contextFactory) {
        // 单独发现预览工具，草案服务可直接取得可信结果而不经过回答模型转述。
        List<McpSyncClient> clients = clientLists.stream().flatMap(List::stream).toList();
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .toolFilter((connectionInfo, tool) -> PREVIEW_TOOLS.contains(tool.name()))
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .toolContextToMcpMetaConverter(metadataConverter)
                .build();
        this.callbacks = Arrays.stream(provider.getToolCallbacks())
                .filter(candidate -> PREVIEW_TOOLS.contains(candidate.getToolDefinition().name()))
                .collect(Collectors.toUnmodifiableMap(
                        candidate -> candidate.getToolDefinition().name(),
                        candidate -> new AuditedToolCallback(candidate, auditService, objectMapper)));
        if (!callbacks.keySet().containsAll(PREVIEW_TOOLS)) {
            throw new IllegalStateException("Ticket operation preview MCP tools are unavailable");
        }
        this.objectMapper = objectMapper;
        this.contextFactory = contextFactory;
    }

    /**
     * 调用可信只读工具获取实时取消预览。
     *
     * @param context 已验证的请求上下文
     * @param orderSn 订单号
     * @return 服务端取消预览
     */
    @Override
    public CancellationPreview previewCancellation(AgentRequestContext context, String orderSn) {
        // 订单号是唯一模型来源，用户身份由签名工具上下文提供。
        return call(
                CANCELLATION_PREVIEW_TOOL,
                Map.of("orderSn", orderSn),
                context,
                CancellationPreview.class);
    }

    /**
     * 调用可信只读工具获取实时退票预览。
     *
     * @param context 已验证的请求上下文
     * @param orderSn 订单号
     * @param type 退款类型
     * @param orderItemIds 部分退款子订单记录标识
     * @return 服务端退票预览
     */
    @Override
    public RefundPreview previewRefund(
            AgentRequestContext context,
            String orderSn,
            Integer type,
            List<String> orderItemIds) {
        // 预览范围由本地草案工具规范化，金额与实际可退明细完全采用业务服务结果。
        return call(
                REFUND_PREVIEW_TOOL,
                Map.of("orderSn", orderSn, "type", type, "orderItemIds", orderItemIds),
                context,
                RefundPreview.class);
    }

    /**
     * 序列化工具参数、调用审计回调并解析结构化结果。
     *
     * @param toolName MCP 工具名
     * @param input 工具参数
     * @param context 已验证请求上下文
     * @param resultType 结果类型
     * @param <T> 结构化结果类型
     * @return 解析后的工具结果
     */
    private <T> T call(
            String toolName,
            Map<String, Object> input,
            AgentRequestContext context,
            Class<T> resultType) {
        try {
            // 所有预览调用都携带显式身份和会话关联字段，并进入统一 MCP 审计。
            String response = callbacks.get(toolName).call(
                    objectMapper.writeValueAsString(input),
                    new ToolContext(contextFactory.create(context)));
            return objectMapper.readValue(response, resultType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Ticket operation preview response is invalid", ex);
        }
    }
}
