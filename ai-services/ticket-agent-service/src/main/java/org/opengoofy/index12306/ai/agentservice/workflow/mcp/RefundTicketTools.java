package org.opengoofy.index12306.ai.agentservice.workflow.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundableOrderOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundableTicketOption;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.RefundResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.service.RefundWorkflowService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 包装本人订单和退票预览 MCP 查询，由服务端解析可退订单及乘车人车票。
 */
@Component
public class RefundTicketTools {

    private static final String ORDER_QUERY_TOOL = "list_my_orders";
    private static final String ORDER_DETAIL_TOOL = "get_my_order_detail";
    private static final String REFUND_PREVIEW_TOOL = "preview_ticket_refund";

    private final ObjectProvider<ToolCallbackProvider> callbackProviders;
    private final McpToolContextFactory toolContextFactory;
    private final RefundWorkflowService workflowService;
    private final ObjectMapper objectMapper;

    /**
     * 创建退票解析工具。
     *
     * @param callbackProviders MCP 和本地工具提供器
     * @param toolContextFactory 显式身份上下文工厂
     * @param workflowService 退票工作流服务
     * @param objectMapper MCP 参数和结果转换器
     */
    public RefundTicketTools(
            ObjectProvider<ToolCallbackProvider> callbackProviders,
            McpToolContextFactory toolContextFactory,
            RefundWorkflowService workflowService,
            ObjectMapper objectMapper) {
        this.callbackProviders = callbackProviders;
        this.toolContextFactory = toolContextFactory;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    /**
     * 定位本人可退订单，并按用户提供的姓名解析可退车票范围。
     *
     * @param orderSn 用户提供的订单号
     * @param trainNumber 用户提供的车次号
     * @param ridingDate 用户提供的乘车日期
     * @param passengerNames 用户明确要求退票的乘车人姓名
     * @param toolContext Spring AI 工具上下文
     * @return 服务端退票解析结果
     */
    @Tool(
            name = "resolve_ticket_refund",
            description = "查询当前账号的可退订单和车票，按订单、车次、日期及乘车人姓名定位退票范围。存在歧义时返回结构化选择状态。")
    public RefundResolutionResult resolveTicketRefund(
            @ToolParam(required = false, description = "用户明确提供的订单号") String orderSn,
            @ToolParam(required = false, description = "用户明确提供的车次号，例如 G9003") String trainNumber,
            @ToolParam(required = false, description = "用户明确提供的乘车日期，格式 yyyy-MM-dd") String ridingDate,
            @ToolParam(required = false, description = "用户明确要求退票的乘车人姓名；未提供时传空列表")
            List<String> passengerNames,
            ToolContext toolContext) {
        AgentRequestContext requestContext = requestContext(toolContext);
        String selectedOrderSn = workflowService.selectedOrderForResolution(
                requestContext.userId(), requestContext.conversationId()).orElse(null);
        String effectiveOrderSn = StringUtils.hasText(orderSn) ? orderSn.trim() : selectedOrderSn;

        // 已有订单号时直接读取详情；否则先加载本人订单列表供服务端定位。
        RefundResolutionResult orderResolution;
        if (StringUtils.hasText(effectiveOrderSn)) {
            RefundableOrderOption order = readOrderDetail(call(
                    ORDER_DETAIL_TOOL, orderDetailArguments(effectiveOrderSn), requestContext));
            orderResolution = workflowService.resolveOrder(
                    requestContext, effectiveOrderSn, trainNumber, ridingDate, passengerNames, List.of(order));
        } else {
            List<RefundableOrderOption> orders = readOrders(call(
                    ORDER_QUERY_TOOL, orderQueryArguments(), requestContext));
            orderResolution = workflowService.resolveOrder(
                    requestContext, null, trainNumber, ridingDate, passengerNames, orders);
        }
        if (orderResolution.status() != RefundResolutionStatus.RESOLVED) {
            return orderResolution;
        }

        // 唯一订单确定后读取安全详情和全部可退预览，车票范围不从模型参数推断。
        String resolvedOrderSn = orderResolution.selectedOrder().orderSn();
        RefundableOrderOption detailedOrder = readOrderDetail(call(
                ORDER_DETAIL_TOOL, orderDetailArguments(resolvedOrderSn), requestContext));
        List<RefundableTicketOption> tickets = readRefundableTickets(call(
                REFUND_PREVIEW_TOOL, refundPreviewArguments(resolvedOrderSn), requestContext));
        return workflowService.resolveTickets(
                requestContext, orderResolution.workflowId(), detailedOrder, tickets);
    }

    /**
     * 调用指定 MCP 只读工具并沿用当前请求的签名身份。
     *
     * @param toolName MCP 工具名
     * @param arguments 工具参数 JSON
     * @param requestContext 当前请求上下文
     * @return MCP 工具结果 JSON
     */
    private String call(String toolName, String arguments, AgentRequestContext requestContext) {
        ToolCallback callback = callbackProviders.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .filter(candidate -> toolName.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("退票查询工具暂时不可用：" + toolName));
        return callback.call(arguments, new ToolContext(toolContextFactory.create(requestContext)));
    }

    /**
     * 创建固定第一页、最大安全页大小的本人订单查询参数。
     *
     * @return MCP 工具参数 JSON
     */
    private String orderQueryArguments() {
        return writeArguments(Map.of("current", 1, "size", 20));
    }

    /**
     * 创建本人订单详情查询参数。
     *
     * @param orderSn 本人订单号
     * @return MCP 工具参数 JSON
     */
    private String orderDetailArguments(String orderSn) {
        return writeArguments(Map.of("orderSn", orderSn.trim()));
    }

    /**
     * 创建全部退票的只读预览参数，用于取得服务端计算的可退车票集合。
     *
     * @param orderSn 本人订单号
     * @return MCP 工具参数 JSON
     */
    private String refundPreviewArguments(String orderSn) {
        return writeArguments(Map.of("orderSn", orderSn.trim(), "type", 1, "orderItemIds", List.of()));
    }

    /**
     * 序列化 MCP 工具参数。
     *
     * @param arguments 待序列化参数
     * @return MCP 工具参数 JSON
     */
    private String writeArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法创建退票查询参数", exception);
        }
    }

    /**
     * 解析本人订单分页结果为退票候选项。
     *
     * @param result MCP 订单分页结果 JSON
     * @return 可退订单候选项
     */
    private List<RefundableOrderOption> readOrders(String result) {
        try {
            JsonNode root = objectMapper.readTree(result);
            JsonNode ordersNode = root == null ? null : root.get("orders");
            if (ordersNode == null || !ordersNode.isArray()) {
                throw new IllegalStateException("本人订单查询结果格式无效");
            }
            // 字段白名单只保留订单定位所需的非敏感信息。
            List<RefundableOrderOption> orders = new ArrayList<>();
            for (JsonNode order : ordersNode) {
                orders.add(toOrder(order, null));
            }
            return List.copyOf(orders);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析本人订单查询结果", exception);
        }
    }

    /**
     * 解析本人订单详情为退票候选项。
     *
     * @param result MCP 订单详情结果 JSON
     * @return 可退订单候选项
     */
    private RefundableOrderOption readOrderDetail(String result) {
        try {
            JsonNode order = objectMapper.readTree(result);
            if (order == null || !order.isObject()) {
                throw new IllegalStateException("本人订单详情结果格式无效");
            }
            JsonNode firstTicket = order.path("tickets").isArray() && !order.path("tickets").isEmpty()
                    ? order.path("tickets").get(0) : null;
            return toOrder(order, firstTicket);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析本人订单详情", exception);
        }
    }

    /**
     * 将订单 JSON 节点转换成退票候选项。
     *
     * @param order 订单 JSON 节点
     * @param firstTicket 第一张车票节点
     * @return 可退订单候选项
     */
    private RefundableOrderOption toOrder(JsonNode order, JsonNode firstTicket) {
        return new RefundableOrderOption(
                text(order, "orderSn"), text(order, "trainNumber"),
                text(order, "departure"), text(order, "arrival"),
                text(order, "ridingDate"), text(order, "departureTime"),
                text(order, "arrivalTime"),
                firstTicket == null ? text(order, "realName") : text(firstTicket, "realName"),
                integer(order, "amount"), integer(order, "status"), bool(order, "canRefund"));
    }

    /**
     * 解析服务端全部退票预览返回的可退车票明细。
     *
     * @param result MCP 退票预览结果 JSON
     * @return 可退车票列表
     */
    private List<RefundableTicketOption> readRefundableTickets(String result) {
        try {
            JsonNode preview = objectMapper.readTree(result);
            if (preview == null || !preview.isObject() || !Boolean.TRUE.equals(bool(preview, "refundable"))) {
                return List.of();
            }
            JsonNode items = preview.get("items");
            if (items == null || !items.isArray()) {
                throw new IllegalStateException("退票预览结果缺少车票明细");
            }
            // 只保存选择卡片和草案范围校验所需字段。
            List<RefundableTicketOption> tickets = new ArrayList<>();
            for (JsonNode item : items) {
                tickets.add(new RefundableTicketOption(
                        text(item, "orderItemId"), text(item, "realName"),
                        integer(item, "seatType"), text(item, "carriageNumber"),
                        text(item, "seatNumber"), integer(item, "status"),
                        integer(item, "refundableAmount")));
            }
            return List.copyOf(tickets);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析退票预览结果", exception);
        }
    }

    /**
     * 从工具上下文恢复当前请求身份。
     *
     * @param toolContext Spring AI 工具上下文
     * @return 当前请求上下文
     */
    private AgentRequestContext requestContext(ToolContext toolContext) {
        Map<String, Object> values = toolContext == null ? Map.of() : toolContext.getContext();
        return new AgentRequestContext(
                text(values, McpToolContextFactory.REQUEST_ID),
                text(values, McpToolContextFactory.USER_ID),
                text(values, McpToolContextFactory.USERNAME),
                text(values, McpToolContextFactory.CONVERSATION_ID),
                text(values, McpToolContextFactory.TURN_ID));
    }

    /**
     * 从工具上下文读取文本字段。
     *
     * @param values 工具上下文字段
     * @param key 字段名
     * @return 字段文本或 null
     */
    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 从 MCP JSON 对象读取文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 字段文本或 null
     */
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * 从 MCP JSON 对象读取整数字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 整数值或 null
     */
    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    /**
     * 从 MCP JSON 对象读取布尔字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 布尔值或 null
     */
    private Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }
}
