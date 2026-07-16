package org.opengoofy.index12306.ai.agentservice.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpSyncClient;
import org.opengoofy.index12306.ai.agentservice.action.ActionStateStore.ClaimedAction;
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

/**
 * 通过与回答模型隔离的 MCP 回调执行已经确认的真实购票操作。
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true")
public class ConfirmedPurchaseMcpExecutor implements ConfirmedPurchaseExecutor {

    private static final String PURCHASE_TOOL = "execute_confirmed_ticket_purchase";

    private final ToolCallback purchaseCallback;
    private final ObjectMapper objectMapper;
    private final McpToolContextFactory contextFactory;

    /**
     * 创建仅发现真实购票工具的专用 MCP 执行器。
     *
     * @param clientLists Spring AI 创建的同步 MCP 客户端集合
     * @param metadataConverter 工具上下文签名转换器
     * @param auditService MCP 工具调用审计服务
     * @param objectMapper JSON 序列化器
     * @param contextFactory MCP 工具上下文工厂
     */
    public ConfirmedPurchaseMcpExecutor(
            ObjectProvider<List<McpSyncClient>> clientLists,
            ToolContextToMcpMetaConverter metadataConverter,
            ToolCallAuditService auditService,
            ObjectMapper objectMapper,
            McpToolContextFactory contextFactory) {
        // 独立发现写工具，避免它进入回答模型使用的 ToolCallbackProvider。
        List<McpSyncClient> clients = clientLists.stream().flatMap(List::stream).toList();
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .toolFilter((connectionInfo, tool) -> PURCHASE_TOOL.equals(tool.name()))
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .toolContextToMcpMetaConverter(metadataConverter)
                .build();
        ToolCallback callback = Arrays.stream(provider.getToolCallbacks())
                .filter(candidate -> PURCHASE_TOOL.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Confirmed purchase MCP tool is unavailable"));
        this.purchaseCallback = new AuditedToolCallback(callback, auditService, objectMapper);
        this.objectMapper = objectMapper;
        this.contextFactory = contextFactory;
    }

    /**
     * 使用已领取执行权的数据库快照调用一次真实购票工具。
     *
     * @param action 已确认且状态为执行中的操作快照
     * @param username 当前登录用户名
     * @return MCP 返回的脱敏购票结果 JSON
     */
    @Override
    public String execute(ClaimedAction action, String username) {
        // 写工具输入只能来自不可变草案，并由服务端补入操作标识。
        String toolInput = buildToolInput(action);
        AgentRequestContext requestContext = new AgentRequestContext(
                action.requestId(), action.userId(), username, action.conversationId(),
                action.turnId(), action.topicId());

        // 操作标识和参数指纹进入 HMAC 元数据，MCP 服务端会再次验证参数未被替换。
        ToolContext toolContext = new ToolContext(contextFactory.createConfirmedAction(
                requestContext, action.actionId(), action.payloadHash()));
        return purchaseCallback.call(toolInput, toolContext);
    }

    /**
     * 将持久化草案转换为真实购票工具输入并补充操作标识。
     *
     * @param action 已领取的操作快照
     * @return 完整工具输入 JSON
     */
    private String buildToolInput(ClaimedAction action) {
        try {
            // 只允许 JSON 对象草案，防止异常持久化数据改变 MCP 参数结构。
            ObjectNode input = (ObjectNode) objectMapper.readTree(action.payloadJson());
            input.put("actionId", action.actionId());
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException | ClassCastException ex) {
            throw new IllegalStateException("Confirmed purchase payload is invalid", ex);
        }
    }
}
