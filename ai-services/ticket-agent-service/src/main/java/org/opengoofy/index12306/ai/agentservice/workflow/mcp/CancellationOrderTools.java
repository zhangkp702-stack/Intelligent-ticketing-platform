package org.opengoofy.index12306.ai.agentservice.workflow.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.CancellableOrderOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.service.CancellationWorkflowService;
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
 * 包装本人订单 MCP 查询，并由服务端完成可取消订单定位和工作流推进。
 */
@Component
public class CancellationOrderTools {

    private static final String ORDER_QUERY_TOOL = "list_my_orders";
    private static final String ORDER_DETAIL_TOOL = "get_my_order_detail";

    private final ObjectProvider<ToolCallbackProvider> callbackProviders;
    private final McpToolContextFactory toolContextFactory;
    private final CancellationWorkflowService workflowService;
    private final ObjectMapper objectMapper;

    /**
     * 创建取消订单解析工具。
     *
     * @param callbackProviders MCP 和本地工具提供器
     * @param toolContextFactory 显式身份上下文工厂
     * @param workflowService 取消订单工作流服务
     * @param objectMapper MCP 参数和结果转换器
     */
    public CancellationOrderTools(
            ObjectProvider<ToolCallbackProvider> callbackProviders,
            McpToolContextFactory toolContextFactory,
            CancellationWorkflowService workflowService,
            ObjectMapper objectMapper) {
        this.callbackProviders = callbackProviders;
        this.toolContextFactory = toolContextFactory;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询当前账号订单并定位可取消目标，无法唯一确定时生成前端订单选择表单。
     *
     * @param orderSn 用户提供的订单号，可为空
     * @param trainNumber 用户提供的车次号，可为空
     * @param ridingDate 用户提供的乘车日期，可为空
     * @param toolContext Spring AI 工具上下文
     * @return 服务端订单定位结果
     */
    @Tool(
            name = "resolve_order_cancellation",
            description = "为取消订单查询当前账号的本人订单并定位可取消目标。未唯一定位时返回结构化订单选择状态。")
    public OrderResolutionResult resolveOrderCancellation(
            @ToolParam(required = false, description = "用户明确提供的订单号") String orderSn,
            @ToolParam(required = false, description = "用户明确提供的车次号，例如 G9003") String trainNumber,
            @ToolParam(required = false, description = "用户明确提供的乘车日期，格式 yyyy-MM-dd") String ridingDate,
            ToolContext toolContext) {
        AgentRequestContext requestContext = requestContext(toolContext);

        // 本地包装器继续调用带签名审计的 MCP 查询，不能绕过下游本人订单边界。
        String queryToolName = StringUtils.hasText(orderSn) ? ORDER_DETAIL_TOOL : ORDER_QUERY_TOOL;
        ToolCallback orderCallback = callbackProviders.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .filter(callback -> queryToolName.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("本人订单查询工具暂时不可用"));
        String result = orderCallback.call(
                StringUtils.hasText(orderSn) ? orderDetailArguments(orderSn) : orderQueryArguments(),
                new ToolContext(toolContextFactory.create(requestContext)));
        List<CancellableOrderOption> orders = StringUtils.hasText(orderSn)
                ? List.of(readOrderDetail(result)) : readOrders(result);

        // 订单筛选和状态推进由服务端完成，模型不能直接从列表中猜测取消目标。
        return workflowService.resolveOrder(
                requestContext, orderSn, trainNumber, ridingDate, orders);
    }

    /**
     * 创建固定第一页、最大安全页大小的订单查询参数。
     *
     * @return MCP 工具 JSON 参数
     */
    private String orderQueryArguments() {
        try {
            // 当前阶段最多展示二十条本人订单，避免模型或页面一次加载无界历史数据。
            return objectMapper.writeValueAsString(Map.of("current", 1, "size", 20));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法创建本人订单查询参数", exception);
        }
    }

    /**
     * 创建本人订单详情查询参数。
     *
     * @param orderSn 用户明确提供的订单号
     * @return MCP 工具 JSON 参数
     */
    private String orderDetailArguments(String orderSn) {
        try {
            // 明确订单号直接查询安全详情，不受订单分页范围限制。
            return objectMapper.writeValueAsString(Map.of("orderSn", orderSn.trim()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法创建本人订单详情查询参数", exception);
        }
    }

    /**
     * 解析 MCP 返回的本人订单分页结果。
     *
     * @param result MCP JSON 结果
     * @return 仅包含取消选择所需字段的订单列表
     */
    private List<CancellableOrderOption> readOrders(String result) {
        try {
            JsonNode root = objectMapper.readTree(result);
            JsonNode ordersNode = root == null ? null : root.get("orders");
            if (ordersNode == null || !ordersNode.isArray()) {
                throw new IllegalStateException("本人订单查询结果格式无效");
            }
            // 字段白名单不读取证件号、手机号或支付凭据。
            List<CancellableOrderOption> orders = new ArrayList<>();
            for (JsonNode order : ordersNode) {
                orders.add(new CancellableOrderOption(
                        text(order, "orderSn"),
                        text(order, "trainNumber"),
                        text(order, "departure"),
                        text(order, "arrival"),
                        text(order, "ridingDate"),
                        text(order, "departureTime"),
                        text(order, "arrivalTime"),
                        text(order, "realName"),
                        integer(order, "amount"),
                        integer(order, "status"),
                        bool(order, "canCancel")));
            }
            return List.copyOf(orders);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析本人订单查询结果", exception);
        }
    }

    /**
     * 解析本人订单详情为取消工作流候选项。
     *
     * @param result MCP JSON 结果
     * @return 订单详情中的取消定位字段
     */
    private CancellableOrderOption readOrderDetail(String result) {
        try {
            JsonNode order = objectMapper.readTree(result);
            if (order == null || !order.isObject()) {
                throw new IllegalStateException("本人订单详情结果格式无效");
            }
            JsonNode firstTicket = order.path("tickets").isArray() && !order.path("tickets").isEmpty()
                    ? order.path("tickets").get(0) : null;
            // 详情视图只提取页面定位订单所需的安全字段。
            return new CancellableOrderOption(
                    text(order, "orderSn"),
                    text(order, "trainNumber"),
                    text(order, "departure"),
                    text(order, "arrival"),
                    text(order, "ridingDate"),
                    text(order, "departureTime"),
                    text(order, "arrivalTime"),
                    firstTicket == null ? null : text(firstTicket, "realName"),
                    null,
                    integer(order, "status"),
                    bool(order, "canCancel"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析本人订单详情", exception);
        }
    }

    /**
     * 从工具上下文恢复当前请求身份。
     */
    private AgentRequestContext requestContext(ToolContext toolContext) {
        Map<String, Object> values = toolContext == null ? Map.of() : toolContext.getContext();
        // 用户和会话身份只接受服务端注入值。
        return new AgentRequestContext(
                text(values, McpToolContextFactory.REQUEST_ID),
                text(values, McpToolContextFactory.USER_ID),
                text(values, McpToolContextFactory.USERNAME),
                text(values, McpToolContextFactory.CONVERSATION_ID),
                text(values, McpToolContextFactory.TURN_ID));
    }

    /**
     * 从工具上下文读取文本字段。
     */
    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 从 MCP JSON 对象读取文本字段。
     */
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * 从 MCP JSON 对象读取整数值。
     */
    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    /**
     * 从 MCP JSON 对象读取布尔值。
     */
    private Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }
}
