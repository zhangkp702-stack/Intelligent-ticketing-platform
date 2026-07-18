package org.opengoofy.index12306.ai.agentservice.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpSyncClient;
import org.opengoofy.index12306.ai.agentservice.action.ActionStateStore.ClaimedAction;
import org.opengoofy.index12306.ai.agentservice.action.domain.AgentActionType;
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
 * 通过与回答模型隔离的 MCP 回调执行已确认的取消订单或退票操作。
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true")
public class ConfirmedTicketOperationMcpExecutor implements ConfirmedTicketOperationExecutor {

    private static final String CANCELLATION_TOOL = "execute_confirmed_order_cancellation";
    private static final String REFUND_TOOL = "execute_confirmed_ticket_refund";
    private static final Set<String> WRITE_TOOLS = Set.of(CANCELLATION_TOOL, REFUND_TOOL);

    private final Map<String, ToolCallback> callbacks;
    private final ObjectMapper objectMapper;
    private final McpToolContextFactory contextFactory;

    /**
     * 创建仅发现取消和退票写工具的专用 MCP 执行器。
     *
     * @param clientLists Spring AI 创建的同步 MCP 客户端集合
     * @param metadataConverter 工具上下文签名转换器
     * @param auditService MCP 工具调用审计服务
     * @param objectMapper JSON 序列化器
     * @param contextFactory MCP 工具上下文工厂
     */
    public ConfirmedTicketOperationMcpExecutor(
            ObjectProvider<List<McpSyncClient>> clientLists,
            ToolContextToMcpMetaConverter metadataConverter,
            ToolCallAuditService auditService,
            ObjectMapper objectMapper,
            McpToolContextFactory contextFactory) {
        // 写工具独立发现且不会进入回答模型使用的只读回调提供器。
        List<McpSyncClient> clients = clientLists.stream().flatMap(List::stream).toList();
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .toolFilter((connectionInfo, tool) -> WRITE_TOOLS.contains(tool.name()))
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .toolContextToMcpMetaConverter(metadataConverter)
                .build();
        this.callbacks = Arrays.stream(provider.getToolCallbacks())
                .filter(candidate -> WRITE_TOOLS.contains(candidate.getToolDefinition().name()))
                .collect(Collectors.toUnmodifiableMap(
                        candidate -> candidate.getToolDefinition().name(),
                        candidate -> new AuditedToolCallback(candidate, auditService, objectMapper)));
        if (!callbacks.keySet().containsAll(WRITE_TOOLS)) {
            throw new IllegalStateException("Confirmed ticket operation MCP tools are unavailable");
        }
        this.objectMapper = objectMapper;
        this.contextFactory = contextFactory;
    }

    /**
     * 根据不可变草案类型调用一次对应的真实写工具。
     *
     * @param action 已领取执行权的草案快照
     * @param username 当前用户名
     * @return MCP 返回的脱敏业务结果 JSON
     */
    @Override
    public String execute(ClaimedAction action, String username) {
        String toolName = switch (action.actionType()) {
            case TICKET_CANCEL -> CANCELLATION_TOOL;
            case TICKET_REFUND -> REFUND_TOOL;
            default -> throw new IllegalArgumentException("Unsupported ticket operation action");
        };

        // 写工具参数来自数据库草案，服务端只补充操作标识和退款幂等请求标识。
        String toolInput = buildToolInput(action);
        AgentRequestContext requestContext = new AgentRequestContext(
                action.requestId(), action.userId(), username, action.conversationId(),
                action.turnId());
        ToolContext toolContext = new ToolContext(contextFactory.createConfirmedAction(
                requestContext, action.actionId(), action.payloadHash()));
        return callbacks.get(toolName).call(toolInput, toolContext);
    }

    /**
     * 将持久化草案转换为取消或退票工具输入。
     *
     * @param action 已领取的操作快照
     * @return 完整工具输入 JSON
     */
    private String buildToolInput(ClaimedAction action) {
        try {
            // 草案必须是 JSON 对象，退款请求标识只用于业务幂等而不参与草案指纹。
            ObjectNode input = (ObjectNode) objectMapper.readTree(action.payloadJson());
            input.put("actionId", action.actionId());
            if (action.actionType() == AgentActionType.TICKET_REFUND) {
                input.put("requestId", action.requestId());
            }
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException | ClassCastException ex) {
            throw new IllegalStateException("Confirmed ticket operation payload is invalid", ex);
        }
    }
}
