package org.opengoofy.index12306.ai.agentservice.workflow.service;

import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.CancellableOrderOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.WorkflowPlanningContext;

import java.util.List;
import java.util.Optional;

/**
 * 定义取消订单工作流中的订单定位、选择和草案边界能力。
 */
public interface CancellationWorkflowService {

    /**
     * 根据用户条件解析需要取消的本人订单。
     *
     * @param requestContext 已验证请求上下文
     * @param requestedOrderSn 用户提供的订单号
     * @param requestedTrainNumber 用户提供的车次号
     * @param requestedRidingDate 用户提供的乘车日期
     * @param orders 本人可取消订单
     * @return 解析或待选择结果
     */
    OrderResolutionResult resolveOrder(
            AgentRequestContext requestContext,
            String requestedOrderSn,
            String requestedTrainNumber,
            String requestedRidingDate,
            List<CancellableOrderOption> orders);

    /**
     * 提交用户选择的取消订单并推进工作流。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param request 订单选择请求
     * @return 订单选择结果
     */
    OrderSelectionResult selectOrder(
            String userId,
            String workflowId,
            OrderSelectionRequest request);

    /**
     * 查询会话中等待订单选择的取消工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 待选择视图
     */
    Optional<OrderSelectionView> findPendingSelection(String userId, String conversationId);

    /**
     * 生成任务规划所需的最小取消工作流上下文。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 规划上下文
     */
    Optional<WorkflowPlanningContext> activeWorkflowContext(String userId, String conversationId);

    /**
     * 查询已经进入草案创建阶段的订单号。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 待取消订单号
     */
    Optional<String> findReadyDraftOrderSn(String userId, String conversationId);

    /**
     * 校验取消草案与服务端工作流一致。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @param orderSn 订单号
     */
    void validateDraft(String userId, String conversationId, String orderSn);

    /**
     * 在草案持久化成功后完成取消工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     */
    void completeAfterDraft(String userId, String conversationId);
}
