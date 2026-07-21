package org.opengoofy.index12306.ai.agentservice.workflow.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.service.PurchaseWorkflowService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 包装乘车人 MCP 查询并由服务端完成姓名匹配和购票工作流推进。
 */
@Component
public class PurchasePassengerTools {

    private static final String PASSENGER_QUERY_TOOL = "list_my_passengers";

    private final ObjectProvider<ToolCallbackProvider> callbackProviders;
    private final McpToolContextFactory toolContextFactory;
    private final PurchaseWorkflowService workflowService;
    private final ObjectMapper objectMapper;

    /**
     * 创建服务端乘车人解析工具。
     *
     * @param callbackProviders MCP 和本地工具提供器
     * @param toolContextFactory 显式身份上下文工厂
     * @param workflowService 购票工作流服务
     * @param objectMapper MCP 结果解析器
     */
    public PurchasePassengerTools(
            ObjectProvider<ToolCallbackProvider> callbackProviders,
            McpToolContextFactory toolContextFactory,
            PurchaseWorkflowService workflowService,
            ObjectMapper objectMapper) {
        this.callbackProviders = callbackProviders;
        this.toolContextFactory = toolContextFactory;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询当前账号乘车人并按姓名唯一匹配，缺失或歧义时生成前端选择表单。
     *
     * @param trainId 余票查询返回的列车标识
     * @param departure 出发站完整名称
     * @param arrival 到达站完整名称
     * @param departureDate 乘车日期
     * @param passengerNames 用户明确提供的姓名列表，可为空
     * @param seatClass 用户明确提供的席别，可为空
     * @param toolContext Spring AI 工具上下文
     * @return 服务端乘车人解析结果
     */
    @Tool(
            name = "resolve_purchase_passengers",
            description = "为购票查询当前账号乘车人并按姓名精确匹配。未提供姓名或存在歧义时返回结构化选择状态，绝不能询问证件号码。")
    public PassengerResolutionResult resolvePurchasePassengers(
            @ToolParam(description = "query_tickets 返回的 trainId") String trainId,
            @ToolParam(description = "出发站完整名称") String departure,
            @ToolParam(description = "到达站完整名称") String arrival,
            @ToolParam(description = "乘车日期，严格使用 yyyy-MM-dd 格式") String departureDate,
            @ToolParam(required = false, description = "用户明确提供的乘车人姓名；没有提供时传空列表")
            List<String> passengerNames,
            @ToolParam(required = false, description = "用户明确提供的语义席别") PurchaseSeatClass seatClass,
            ToolContext toolContext) {
        AgentRequestContext requestContext = requestContext(toolContext);

        // 远端查询仍使用原 MCP 审计和签名回调，本地工具不能绕过身份边界直接访问用户服务。
        ToolCallback passengerCallback = callbackProviders.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .filter(callback -> PASSENGER_QUERY_TOOL.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("乘车人查询工具暂时不可用"));
        String result = passengerCallback.call(
                "{}",
                new ToolContext(toolContextFactory.create(requestContext)));
        List<PassengerOption> options = readPassengerOptions(result);

        // 匹配和工作流推进全部在服务端完成，模型只接收 passengerId 和下一步状态。
        return workflowService.resolvePassengers(
                requestContext,
                trainId,
                departure,
                arrival,
                departureDate,
                passengerNames,
                seatClass,
                options);
    }

    /**
     * 从 Spring AI 工具上下文恢复当前请求身份。
     */
    private AgentRequestContext requestContext(ToolContext toolContext) {
        Map<String, Object> values = toolContext == null ? Map.of() : toolContext.getContext();
        // 请求身份只来自服务端注入的 ToolContext，模型参数不能覆盖用户和会话边界。
        return new AgentRequestContext(
                text(values, McpToolContextFactory.REQUEST_ID),
                text(values, McpToolContextFactory.USER_ID),
                text(values, McpToolContextFactory.USERNAME),
                text(values, McpToolContextFactory.CONVERSATION_ID),
                text(values, McpToolContextFactory.TURN_ID));
    }

    /**
     * 解析 MCP 返回的脱敏乘车人数组。
     */
    private List<PassengerOption> readPassengerOptions(String result) {
        try {
            JsonNode root = objectMapper.readTree(result);
            if (!root.isArray()) {
                throw new IllegalStateException("乘车人查询结果格式无效");
            }
            // 字段白名单不读取手机号，也不会把完整证件号写入工作流。
            List<PassengerOption> options = new ArrayList<>();
            for (JsonNode passenger : root) {
                options.add(new PassengerOption(
                        text(passenger, "passengerId"),
                        text(passenger, "realName"),
                        text(passenger, "maskedIdCard"),
                        integer(passenger, "discountType"),
                        integer(passenger, "verifyStatus")));
            }
            return List.copyOf(options);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析乘车人查询结果", exception);
        }
    }

    /**
     * 从工具上下文读取字符串字段。
     */
    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 从 MCP JSON 对象读取允许为空的文本字段。
     */
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * 从 MCP JSON 对象读取允许为空的整数值。
     */
    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }
}
