package org.opengoofy.index12306.ai.agentservice.action.mcp;

import org.opengoofy.index12306.ai.agentservice.action.service.ActionDraftCreationTracker;
import org.opengoofy.index12306.ai.agentservice.action.service.TicketOperationActionService;


import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.TicketOperationDraftResult;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 允许模型生成取消和退票草案但绝不直接修改订单的本地 Spring AI 工具。
 */
@Component
public class TicketOperationDraftTools {

    private final TicketOperationActionService actionService;
    private final ActionDraftCreationTracker actionDraftCreationTracker;

    /**
     * 创建订单操作草案工具。
     *
     * @param actionService 取消和退票草案服务
     * @param actionDraftCreationTracker 本轮草案创建信号
     */
    public TicketOperationDraftTools(
            TicketOperationActionService actionService,
            ActionDraftCreationTracker actionDraftCreationTracker) {
        this.actionService = actionService;
        this.actionDraftCreationTracker = actionDraftCreationTracker;
    }

    /**
     * 对用户明确选择的本人订单生成待确认取消草案。
     *
     * @param orderSn 当前用户订单号
     * @param toolContext 服务端注入的用户和轮次上下文
     * @return 不含确认令牌的草案摘要
     */
    @Tool(
            name = "prepare_order_cancellation",
            description = "读取实时订单状态并生成待用户确认的取消草案，不会真正取消订单。")
    public TicketOperationDraftResult prepareOrderCancellation(
            @ToolParam(description = "list_my_orders 或 get_my_order_detail 返回的 orderSn") String orderSn,
            ToolContext toolContext) {
        // 本地工具只创建可信状态快照，真实取消必须由独立确认接口继续执行。
        AgentRequestContext context = requestContext(toolContext);
        TicketOperationDraftResult result = actionService.prepareCancellation(context, orderSn);
        // 预览和草案持久化都成功后再标记本轮，失败请求不会产生确认事件。
        actionDraftCreationTracker.markCreated(context.turnId());
        return result;
    }

    /**
     * 对用户明确选择的本人车票生成待确认退票草案。
     *
     * @param orderSn 当前用户订单号
     * @param type 退款类型
     * @param orderItemIds 部分退款子订单记录标识
     * @param toolContext 服务端注入的用户和轮次上下文
     * @return 不含确认令牌的草案摘要
     */
    @Tool(
            name = "prepare_ticket_refund",
            description = "读取实时可退车票和预计金额并生成待用户确认的退票草案，不会发起真实退款。")
    public TicketOperationDraftResult prepareTicketRefund(
            @ToolParam(description = "当前用户订单号") String orderSn,
            @ToolParam(description = "退款类型：0 部分退款，1 全部退款") Integer type,
            @ToolParam(required = false, description = "部分退款时填写订单详情返回的子订单 ID")
            List<String> orderItemIds,
            ToolContext toolContext) {
        // 退款范围和金额由服务端预览重新计算，模型不能直接写入草案金额。
        AgentRequestContext context = requestContext(toolContext);
        TicketOperationDraftResult result = actionService.prepareRefund(
                context, orderSn, type, orderItemIds);
        // 只有真实保存的退票草案才允许对话层继续读取数据库确认视图。
        actionDraftCreationTracker.markCreated(context.turnId());
        return result;
    }

    /**
     * 从模型不可修改的工具上下文恢复当前请求身份。
     *
     * @param toolContext Spring AI 工具上下文
     * @return 显式请求上下文
     */
    private AgentRequestContext requestContext(ToolContext toolContext) {
        Map<String, Object> values = toolContext == null ? Map.of() : toolContext.getContext();
        // 所有身份字段由服务端创建，草案工具不接受模型提供的用户标识。
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
     * @param values 工具上下文属性
     * @param key 字段名
     * @return 字段文本或 null
     */
    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }
}
