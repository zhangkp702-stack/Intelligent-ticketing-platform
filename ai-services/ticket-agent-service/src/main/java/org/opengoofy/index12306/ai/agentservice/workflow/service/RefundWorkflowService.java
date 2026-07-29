package org.opengoofy.index12306.ai.agentservice.workflow.service;

import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundOrderSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundOrderSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundTicketSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundTicketSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundWorkflowContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundableOrderOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundableTicketOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.WorkflowInteractionView;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.WorkflowPlanningContext;

import java.util.List;
import java.util.Optional;

/**
 * 定义退票工作流中的订单定位、车票选择和草案边界能力。
 */
public interface RefundWorkflowService {

    /**
     * 根据用户条件解析需要退票的本人订单。
     *
     * @param requestContext 已验证请求上下文
     * @param requestedOrderSn 用户提供的订单号
     * @param requestedTrainNumber 用户提供的车次号
     * @param requestedRidingDate 用户提供的乘车日期
     * @param requestedPassengerNames 用户提供的乘车人姓名
     * @param orders 本人可退订单
     * @return 解析或待选择结果
     */
    RefundResolutionResult resolveOrder(
            AgentRequestContext requestContext,
            String requestedOrderSn,
            String requestedTrainNumber,
            String requestedRidingDate,
            List<String> requestedPassengerNames,
            List<RefundableOrderOption> orders);

    /**
     * 解析选中订单下可退车票并推进工作流。
     *
     * @param requestContext 已验证请求上下文
     * @param workflowId 工作流标识
     * @param order 已选订单
     * @param tickets 可退车票
     * @return 车票解析或待选择结果
     */
    RefundResolutionResult resolveTickets(
            AgentRequestContext requestContext,
            String workflowId,
            RefundableOrderOption order,
            List<RefundableTicketOption> tickets);

    /**
     * 提交用户选择的退票订单。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param request 订单选择请求
     * @return 订单选择结果
     */
    RefundOrderSelectionResult selectOrder(
            String userId,
            String workflowId,
            RefundOrderSelectionRequest request);

    /**
     * 提交用户选择的退票车票。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param request 车票选择请求
     * @return 车票选择结果
     */
    RefundTicketSelectionResult selectTickets(
            String userId,
            String workflowId,
            RefundTicketSelectionRequest request);

    /**
     * 查询会话中等待用户选择的退票工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 待交互视图
     */
    Optional<WorkflowInteractionView> findPendingSelection(String userId, String conversationId);

    /**
     * 生成任务规划所需的最小退票工作流上下文。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 规划上下文
     */
    Optional<WorkflowPlanningContext> activeWorkflowContext(String userId, String conversationId);

    /**
     * 查询已经进入草案创建阶段的退票上下文。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 可创建草案的上下文
     */
    Optional<RefundWorkflowContext> findReadyDraftContext(String userId, String conversationId);

    /**
     * 校验退票草案与服务端工作流一致。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @param orderSn 订单号
     * @param refundType 退款类型
     * @param orderItemIds 子订单标识
     */
    void validateDraft(
            String userId,
            String conversationId,
            String orderSn,
            Integer refundType,
            List<String> orderItemIds);

    /**
     * 在草案持久化成功后完成退票工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     */
    void completeAfterDraft(String userId, String conversationId);

    /**
     * 查询已完成订单选择且等待车票解析的订单号。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 已选订单号
     */
    Optional<String> selectedOrderForResolution(String userId, String conversationId);
}
