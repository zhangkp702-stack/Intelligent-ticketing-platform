package org.opengoofy.index12306.ai.agentservice.workflow.service;

import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PurchaseWorkflowContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.WorkflowPlanningContext;

import java.util.List;
import java.util.Optional;

/**
 * 定义购票工作流中的乘车人匹配、选择和草案边界能力。
 */
public interface PurchaseWorkflowService {

    /**
     * 根据用户输入和本人乘车人列表解析购票乘车人。
     *
     * @param requestContext 已验证请求上下文
     * @param trainId 车次标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param departureDate 出发日期
     * @param passengerNames 用户提供的乘车人姓名
     * @param seatClass 选择席别
     * @param options 本人乘车人候选
     * @return 解析或待选择结果
     */
    PassengerResolutionResult resolvePassengers(
            AgentRequestContext requestContext,
            String trainId,
            String departure,
            String arrival,
            String departureDate,
            List<String> passengerNames,
            PurchaseSeatClass seatClass,
            List<PassengerOption> options);

    /**
     * 提交用户选择的乘车人并推进工作流。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param request 乘车人选择请求
     * @return 选择结果
     */
    PassengerSelectionResult selectPassengers(
            String userId,
            String workflowId,
            PassengerSelectionRequest request);

    /**
     * 查询会话中等待乘车人选择的工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 待选择视图
     */
    Optional<PassengerSelectionView> findPendingSelection(String userId, String conversationId);

    /**
     * 查询已经进入草案创建阶段的购票上下文。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 可创建草案的上下文
     */
    Optional<PurchaseWorkflowContext> findReadyDraftContext(String userId, String conversationId);

    /**
     * 生成任务规划所需的最小工作流上下文。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 规划上下文
     */
    Optional<WorkflowPlanningContext> activeWorkflowContext(String userId, String conversationId);

    /**
     * 校验购票草案与服务端工作流状态一致。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @param trainId 车次标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param departureDate 出发日期
     * @param passengerIds 乘车人标识
     */
    void validateDraft(
            String userId,
            String conversationId,
            String trainId,
            String departure,
            String arrival,
            String departureDate,
            List<String> passengerIds);

    /**
     * 在草案持久化成功后完成购票工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     */
    void completeAfterDraft(String userId, String conversationId);
}
