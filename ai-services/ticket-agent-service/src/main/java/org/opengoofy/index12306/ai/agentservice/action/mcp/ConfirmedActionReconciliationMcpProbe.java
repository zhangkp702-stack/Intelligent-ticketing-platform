package org.opengoofy.index12306.ai.agentservice.action.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService.DownstreamResult;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService.DownstreamStatus;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService.WorkItem;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.audit.AuditedToolCallback;
import org.opengoofy.index12306.ai.agentservice.mcp.audit.service.ToolCallAuditService;
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
 * 通过隔离的只读 MCP 工具查询 actionId 对应的 ticket-service 操作事实。
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true")
public class ConfirmedActionReconciliationMcpProbe implements ActionReconciliationProbe {

    private static final String STATUS_TOOL = "query_confirmed_action_status";

    private final ToolCallback statusCallback;
    private final ObjectMapper objectMapper;
    private final McpToolContextFactory contextFactory;

    /**
     * 创建仅能发现对账状态工具的 MCP 查询端口。
     *
     * @param clientLists MCP 同步客户端
     * @param metadataConverter 身份签名转换器
     * @param auditService 工具调用审计服务
     * @param objectMapper JSON 解析器
     * @param contextFactory MCP 上下文工厂
     */
    public ConfirmedActionReconciliationMcpProbe(
            ObjectProvider<List<McpSyncClient>> clientLists,
            ToolContextToMcpMetaConverter metadataConverter,
            ToolCallAuditService auditService,
            ObjectMapper objectMapper,
            McpToolContextFactory contextFactory) {
        // 对账进程只发现一个只读工具，不能获得任何真实写工具回调。
        List<McpSyncClient> clients = clientLists.stream().flatMap(List::stream).toList();
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .toolFilter((connectionInfo, tool) -> STATUS_TOOL.equals(tool.name()))
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .toolContextToMcpMetaConverter(metadataConverter)
                .build();
        ToolCallback callback = Arrays.stream(provider.getToolCallbacks())
                .filter(candidate -> STATUS_TOOL.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Action reconciliation MCP tool is unavailable"));
        this.statusCallback = new AuditedToolCallback(callback, auditService, objectMapper);
        this.objectMapper = objectMapper;
        this.contextFactory = contextFactory;
    }

    /**
     * 携带持久化 actionId 和用户身份执行一次只读权威状态查询。
     *
     * @param workItem 已领取对账事件
     * @return 下游持久化状态和脱敏结果
     */
    @Override
    public DownstreamResult query(WorkItem workItem) {
        try {
            // 状态接口只按 userId 鉴权；历史草案未保存 username，查询链路使用 userId 填充必填审计字段。
            AgentRequestContext requestContext = new AgentRequestContext(
                    "reconcile-" + workItem.eventId(), workItem.userId(), workItem.userId(),
                    workItem.conversationId(), workItem.turnId());
            String input = objectMapper.createObjectNode()
                    .put("actionId", workItem.actionId())
                    .toString();
            String response = statusCallback.call(input, new ToolContext(
                    contextFactory.createConfirmedAction(
                            requestContext, workItem.actionId(), workItem.payloadHash())));
            JsonNode json = objectMapper.readTree(response);
            return new DownstreamResult(
                    requiredText(json, "actionId"),
                    requiredText(json, "operationType"),
                    DownstreamStatus.valueOf(requiredText(json, "status")),
                    json.path("safeResultJson").isNull() ? null : json.path("safeResultJson").asText(null),
                    json.path("failureMessage").isNull() ? null : json.path("failureMessage").asText(null));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Action reconciliation MCP response is invalid", exception);
        }
    }

    /**
     * 读取 MCP 响应的必填文本字段。
     *
     * @param json MCP 响应
     * @param field 字段名
     * @return 非空文本
     */
    private String requiredText(JsonNode json, String field) {
        String value = json.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MCP response field is missing: " + field);
        }
        return value;
    }
}
