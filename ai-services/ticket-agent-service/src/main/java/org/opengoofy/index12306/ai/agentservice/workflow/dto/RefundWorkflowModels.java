package org.opengoofy.index12306.ai.agentservice.workflow.dto;

import org.opengoofy.index12306.ai.agentservice.workflow.enums.RefundResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;

import java.util.List;

/**
 * 退票工作流的持久化上下文、解析结果和前端选择视图。
 */
public final class RefundWorkflowModels {

    private RefundWorkflowModels() {
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
     * @param canRefund 是否允许退票
     */
    public record RefundableOrderOption(
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
            Boolean canRefund) {
    }

    /**
     * @param orderItemId 子订单记录标识
     * @param realName 乘车人姓名
     * @param seatType 席别编码
     * @param carriageNumber 车厢号
     * @param seatNumber 座位号
     * @param status 车票状态
     * @param refundableAmount 可退金额
     */
    public record RefundableTicketOption(
            String orderItemId,
            String realName,
            Integer seatType,
            String carriageNumber,
            String seatNumber,
            Integer status,
            Integer refundableAmount) {
    }

    /**
     * @param requestedOrderSn 用户提供的订单号
     * @param requestedTrainNumber 用户提供的车次号
     * @param requestedRidingDate 用户提供的乘车日期
     * @param requestedPassengerNames 用户提供的退票乘车人姓名
     * @param orderOptions 可退订单候选项
     * @param selectedOrderSn 已确认订单号
     * @param ticketOptions 已确认订单的可退车票
     * @param selectedOrderItemIds 已确认的退票范围
     * @param refundType 退款类型，0 为部分退票，1 为全部退票
     */
    public record RefundWorkflowContext(
            String requestedOrderSn,
            String requestedTrainNumber,
            String requestedRidingDate,
            List<String> requestedPassengerNames,
            List<RefundableOrderOption> orderOptions,
            String selectedOrderSn,
            List<RefundableTicketOption> ticketOptions,
            List<String> selectedOrderItemIds,
            Integer refundType) {
    }

    /**
     * @param status 退票解析状态
     * @param workflowId 工作流标识
     * @param selectedOrder 已定位订单
     * @param selectedTickets 已定位车票
     * @param refundType 退款类型
     * @param message 下一步说明
     */
    public record RefundResolutionResult(
            RefundResolutionStatus status,
            String workflowId,
            RefundableOrderOption selectedOrder,
            List<RefundableTicketOption> selectedTickets,
            Integer refundType,
            String message) {
    }

    /**
     * @param workflowId 工作流标识
     * @param stage 当前阶段
     * @param prompt 前端提示
     * @param orders 可退订单列表
     */
    public record RefundOrderSelectionView(
            String workflowId,
            WorkflowStage stage,
            String prompt,
            List<RefundableOrderOption> orders) implements WorkflowInteractionView {
    }

    /**
     * @param workflowId 工作流标识
     * @param stage 当前阶段
     * @param prompt 前端提示
     * @param orderSn 已确认订单号
     * @param tickets 可退车票列表
     */
    public record RefundTicketSelectionView(
            String workflowId,
            WorkflowStage stage,
            String prompt,
            String orderSn,
            List<RefundableTicketOption> tickets) implements WorkflowInteractionView {
    }

    /**
     * @param orderSn 用户选择的可退订单号
     */
    public record RefundOrderSelectionRequest(String orderSn) {
    }

    /**
     * @param orderItemIds 用户勾选的可退车票标识
     */
    public record RefundTicketSelectionRequest(List<String> orderItemIds) {
    }

    /**
     * @param workflowId 工作流标识
     * @param stage 选择后的阶段
     * @param orderSn 已选择订单号
     */
    public record RefundOrderSelectionResult(
            String workflowId,
            WorkflowStage stage,
            String orderSn) {
    }

    /**
     * @param workflowId 工作流标识
     * @param stage 选择后的阶段
     * @param orderSn 已选择订单号
     * @param orderItemIds 已选择车票标识
     * @param refundType 退款类型
     */
    public record RefundTicketSelectionResult(
            String workflowId,
            WorkflowStage stage,
            String orderSn,
            List<String> orderItemIds,
            Integer refundType) {
    }
}
