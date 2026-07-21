package org.opengoofy.index12306.ai.agentservice.workflow.dto;

import org.opengoofy.index12306.ai.agentservice.workflow.enums.PassengerResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;

import java.util.List;

/**
 * 购票工作流持久化上下文、模型工具结果和前端选择视图集合。
 */
public final class PurchaseWorkflowModels {

    private PurchaseWorkflowModels() {
    }

    /**
     * @param passengerId 乘车人业务标识
     * @param realName 真实姓名
     * @param maskedIdCard 脱敏证件号
     * @param discountType 旅客优惠类型
     * @param verifyStatus 核验状态
     */
    public record PassengerOption(
            String passengerId,
            String realName,
            String maskedIdCard,
            Integer discountType,
            Integer verifyStatus) {
    }

    /**
     * @param trainId 余票查询返回的列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param departureDate 乘车日期
     * @param requestedPassengerNames 用户提供的乘车人姓名
     * @param passengerOptions 当前账号可选择的乘车人
     * @param selectedPassengerIds 已由服务端解析或用户勾选的乘车人标识
     * @param seatType 已确定的席别编码
     * @param chooseSeats 可选座位偏好
     */
    public record PurchaseWorkflowContext(
            String trainId,
            String departure,
            String arrival,
            String departureDate,
            List<String> requestedPassengerNames,
            List<PassengerOption> passengerOptions,
            List<String> selectedPassengerIds,
            Integer seatType,
            List<String> chooseSeats) {
    }

    /**
     * @param passengerId 已匹配的乘车人标识
     * @param realName 已匹配的姓名
     */
    public record ResolvedPassenger(String passengerId, String realName) {
    }

    /**
     * @param status 解析结果
     * @param workflowId 工作流标识
     * @param resolvedPassengers 唯一匹配的乘车人
     * @param unmatchedNames 没有唯一匹配的输入姓名
     * @param message 下一步处理说明
     */
    public record PassengerResolutionResult(
            PassengerResolutionStatus status,
            String workflowId,
            List<ResolvedPassenger> resolvedPassengers,
            List<String> unmatchedNames,
            String message) {
    }

    /**
     * @param workflowId 工作流标识
     * @param stage 当前工作流阶段
     * @param prompt 前端选择提示
     * @param options 可选择的乘车人
     * @param minSelections 最少选择数量
     * @param maxSelections 最多选择数量
     */
    public record PassengerSelectionView(
            String workflowId,
            WorkflowStage stage,
            String prompt,
            List<PassengerOption> options,
            int minSelections,
            int maxSelections) implements WorkflowInteractionView {
    }

    /**
     * @param passengerIds 用户勾选的乘车人标识
     */
    public record PassengerSelectionRequest(List<String> passengerIds) {
    }

    /**
     * @param workflowId 工作流标识
     * @param stage 选择后的下一阶段
     * @param selectedPassengers 已选择的脱敏乘车人
     */
    public record PassengerSelectionResult(
            String workflowId,
            WorkflowStage stage,
            List<ResolvedPassenger> selectedPassengers) {
    }
}
