package org.opengoofy.index12306.ai.agentservice.workflow.dto;

import org.opengoofy.index12306.ai.agentservice.workflow.enums.OrderResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;

import java.util.List;

/**
 * 取消订单工作流的持久化上下文、工具结果和前端订单选择视图。
 */
public final class CancellationWorkflowModels {

    private CancellationWorkflowModels() {
    }

    /**
     * @param orderSn 本人订单号
     * @param trainNumber 车次号
     * @param departure 出发站
     * @param arrival 到达站
     * @param ridingDate 乘车日期
     * @param departureTime 发车时间
     * @param arrivalTime 到达时间
     * @param realName 乘车人姓名
     * @param amount 订单金额
     * @param status 订单状态
     * @param canCancel 是否允许取消
     */
    public record CancellableOrderOption(
            String orderSn,
            String trainNumber,
            String departure,
            String arrival,
            String ridingDate,
            String departureTime,
            String arrivalTime,
            String realName,
            Integer amount,
            Integer status,
            Boolean canCancel) {
    }

    /**
     * @param requestedOrderSn 用户提供的订单号
     * @param requestedTrainNumber 用户提供的车次号
     * @param requestedRidingDate 用户提供的乘车日期
     * @param orderOptions 当前账号的可取消订单候选项
     * @param selectedOrderSn 服务端或用户已经选定的订单号
     */
    public record CancellationWorkflowContext(
            String requestedOrderSn,
            String requestedTrainNumber,
            String requestedRidingDate,
            List<CancellableOrderOption> orderOptions,
            String selectedOrderSn) {
    }

    /**
     * @param status 订单定位结果
     * @param workflowId 工作流标识
     * @param selectedOrder 已唯一定位的订单
     * @param message 下一步说明
     */
    public record OrderResolutionResult(
            OrderResolutionStatus status,
            String workflowId,
            CancellableOrderOption selectedOrder,
            String message) {
    }

    /**
     * @param workflowId 工作流标识
     * @param stage 当前阶段
     * @param prompt 前端提示
     * @param orders 可选择订单
     */
    public record OrderSelectionView(
            String workflowId,
            WorkflowStage stage,
            String prompt,
            List<CancellableOrderOption> orders) implements WorkflowInteractionView {
    }

    /**
     * @param orderSn 用户勾选的订单号
     */
    public record OrderSelectionRequest(String orderSn) {
    }

    /**
     * @param workflowId 工作流标识
     * @param stage 选择后的阶段
     * @param selectedOrder 已选择订单
     */
    public record OrderSelectionResult(
            String workflowId,
            WorkflowStage stage,
            CancellableOrderOption selectedOrder) {
    }
}
