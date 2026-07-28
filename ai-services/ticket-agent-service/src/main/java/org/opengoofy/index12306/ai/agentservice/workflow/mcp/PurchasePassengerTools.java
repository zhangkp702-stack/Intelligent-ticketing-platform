package org.opengoofy.index12306.ai.agentservice.workflow.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.PassengerResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.service.PurchaseWorkflowService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 包装乘车人 MCP 查询并由服务端完成姓名匹配和购票工作流推进。
 */
@Component
public class PurchasePassengerTools {

    private static final String PASSENGER_QUERY_TOOL = "find_my_passengers_by_name";

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
     * 提取用户明确提供的姓名并逐个定向查询当前账号乘车人，匹配成功后推进购票工作流。
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
    public PassengerResolutionResult resolvePurchasePassengers(
            String trainId,
            String departure,
            String arrival,
            String departureDate,
            List<String> passengerNames,
            PurchaseSeatClass seatClass,
            ToolContext toolContext) {
        AgentRequestContext requestContext = requestContext(toolContext);
        List<String> normalizedNames = normalizePassengerNames(passengerNames);
        if (normalizedNames.isEmpty()) {
            // 定向查询必须有用户明确提供的姓名，不再通过全量列表让模型猜测乘车人。
            PassengerResolutionResult resolution = new PassengerResolutionResult(
                    PassengerResolutionStatus.NAME_REQUIRED,
                    null,
                    List.of(),
                    List.of(),
                    "请先提供需要购票的乘车人姓名");
            return resolution;
        }

        // 定向查询仍使用 MCP 的审计和签名回调，本地工具不能绕过身份边界直接访问用户服务。
        ToolCallback passengerCallback = callbackProviders.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .filter(callback -> PASSENGER_QUERY_TOOL.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("乘车人查询工具暂时不可用"));

        // 每个姓名都直接调用按姓名查询，不加载当前账号的全量乘车人列表。
        List<PassengerOption> options = new ArrayList<>();
        for (String passengerName : normalizedNames) {
            String arguments;
            try {
                arguments = objectMapper.writeValueAsString(Map.of("realName", passengerName));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("无法生成乘车人姓名查询参数", exception);
            }
            String result = passengerCallback.call(
                    arguments,
                    new ToolContext(toolContextFactory.create(requestContext)));
            for (PassengerOption option : readPassengerOptions(result)) {
                if (options.stream().noneMatch(existing -> existing.passengerId().equals(option.passengerId()))) {
                    options.add(option);
                }
            }
        }
        if (options.isEmpty()) {
            // 定向查询为空只表示这些姓名未匹配，不能误报为当前账号没有任何乘车人。
            PassengerResolutionResult resolution = new PassengerResolutionResult(
                    PassengerResolutionStatus.NOT_FOUND,
                    null,
                    List.of(),
                    normalizedNames,
                    "当前账号下未找到用户提供姓名对应的乘车人");
            return resolution;
        }

        // 匹配和工作流推进全部在服务端完成，模型只接收 passengerId 和下一步状态。
        PassengerResolutionResult resolution = workflowService.resolvePassengers(
                requestContext,
                trainId,
                departure,
                arrival,
                departureDate,
                normalizedNames,
                seatClass,
                options);
        return resolution;
    }

    /**
     * 规范化模型从用户请求中提取的乘车人姓名，删除空值、首尾空格和重复姓名。
     *
     * @param passengerNames 用户在购票请求中明确提供的乘车人姓名
     * @return 可直接用于精确查询的姓名列表
     */
    private List<String> normalizePassengerNames(List<String> passengerNames) {
        // 姓名仅做格式清理，不做模糊匹配或别名推断。
        if (passengerNames == null) {
            return List.of();
        }
        return passengerNames.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
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
                String passengerId = text(passenger, "passengerId");
                String realName = text(passenger, "realName");
                // 非空数组中缺少业务标识或姓名属于协议错误，不能降级成“账号没有乘车人”。
                if (!StringUtils.hasText(passengerId) || !StringUtils.hasText(realName)) {
                    throw new IllegalStateException("乘车人查询结果缺少业务标识或姓名");
                }
                options.add(new PassengerOption(
                        passengerId,
                        realName,
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
