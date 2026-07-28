package org.opengoofy.index12306.ai.agentservice.action.mcp;

import org.opengoofy.index12306.ai.agentservice.action.service.TicketOperationActionService;


import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.TicketOperationDraftResult;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.service.CancellationWorkflowService;
import org.opengoofy.index12306.ai.agentservice.workflow.service.RefundWorkflowService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 供固定取消和退票链生成待确认草案的内部组件，绝不直接修改订单。
 */
@Component
public class TicketOperationDraftTools {

    private final TicketOperationActionService actionService;
    private final CancellationWorkflowService cancellationWorkflowService;
    private final RefundWorkflowService refundWorkflowService;

    /**
     * 创建订单操作草案工具。
     *
     * @param actionService 取消和退票草案服务
     * @param cancellationWorkflowService 取消订单工作流服务
     * @param refundWorkflowService 退票工作流服务
     */
    public TicketOperationDraftTools(
            TicketOperationActionService actionService,
            CancellationWorkflowService cancellationWorkflowService,
            RefundWorkflowService refundWorkflowService) {
        this.actionService = actionService;
        this.cancellationWorkflowService = cancellationWorkflowService;
        this.refundWorkflowService = refundWorkflowService;
    }

    /**
     * 对用户明确选择的本人订单生成待确认取消草案。
     *
     * @param orderSn 当前用户订单号
     * @param toolContext 服务端注入的用户和轮次上下文
     * @return 不含确认令牌的草案摘要
     */
    public TicketOperationDraftResult prepareOrderCancellation(
            String orderSn,
            ToolContext toolContext) {
        // 本地工具只创建可信状态快照，真实取消必须由独立确认接口继续执行。
        AgentRequestContext context = requestContext(toolContext);
        // 取消草案只能使用服务端唯一匹配或用户勾选的本人订单。
        cancellationWorkflowService.validateDraft(
                context.userId(), context.conversationId(), orderSn);
        TicketOperationDraftResult result = actionService.prepareCancellation(context, orderSn);
        // 草案持久化成功后再结束取消订单工作流，真实取消仍等待独立确认。
        cancellationWorkflowService.completeAfterDraft(context.userId(), context.conversationId());
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
    public TicketOperationDraftResult prepareTicketRefund(
            String orderSn,
            Integer type,
            List<String> orderItemIds,
            ToolContext toolContext) {
        // 退款范围和金额由服务端预览重新计算，模型不能直接写入草案金额。
        AgentRequestContext context = requestContext(toolContext);
        // 退票草案只能使用服务端按姓名匹配或用户勾选后持久化的订单和车票范围。
        refundWorkflowService.validateDraft(
                context.userId(), context.conversationId(), orderSn, type, orderItemIds);
        TicketOperationDraftResult result = actionService.prepareRefund(
                context, orderSn, type, orderItemIds);
        // 草案保存成功后结束退票工作流，真实退款仍等待独立确认。
        refundWorkflowService.completeAfterDraft(context.userId(), context.conversationId());
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
