package org.opengoofy.index12306.ai.agentservice.action.dto;


import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;

import java.time.Instant;
import java.util.List;

/**
 * 取消订单和退票草案、预览及脱敏执行结果的数据模型集合。
 */
public final class TicketOperationActionModels {

    /**
     * 工具类不允许实例化。
     */
    private TicketOperationActionModels() {
    }

    /**
     * @param orderSn 订单号
     * @param orderStatus 创建草案时的订单状态
     */
    public record CancellationPayload(String orderSn, Integer orderStatus) {
    }

    /**
     * @param orderSn 订单号
     * @param type 退款类型，0 为部分退款，1 为全部退款
     * @param orderItemIds 选中的子订单记录标识
     * @param expectedRefundAmount 创建草案时服务端计算的预计退款金额
     */
    public record RefundPayload(
            String orderSn,
            Integer type,
            List<String> orderItemIds,
            Integer expectedRefundAmount) {
    }

    /**
     * @param orderSn 订单号
     * @param orderStatus 订单状态
     * @param canCancel 是否允许取消
     * @param canPay 是否允许支付
     * @param canRefund 是否允许退票
     * @param reason 不可操作原因
     */
    public record CancellationPreview(
            String orderSn,
            Integer orderStatus,
            Boolean canCancel,
            Boolean canPay,
            Boolean canRefund,
            String reason) {
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
    public record RefundableTicket(
            String orderItemId,
            String realName,
            Integer seatType,
            String carriageNumber,
            String seatNumber,
            Integer status,
            Integer refundableAmount) {
    }

    /**
     * @param orderSn 订单号
     * @param type 退款类型
     * @param refundable 是否允许按当前范围退票
     * @param refundAmount 预计退款金额
     * @param items 可退车票明细
     * @param reason 不可退原因
     */
    public record RefundPreview(
            String orderSn,
            Integer type,
            Boolean refundable,
            Integer refundAmount,
            List<RefundableTicket> items,
            String reason) {
    }

    /**
     * @param actionId 草案标识
     * @param status 草案状态
     * @param summary 供模型说明的安全摘要
     * @param confirmationExpiresAt 确认截止时间
     */
    public record TicketOperationDraftResult(
            String actionId,
            AgentActionStatus status,
            String summary,
            Instant confirmationExpiresAt) {
    }

    /**
     * @param orderSn 已取消订单号
     * @param cancelled 是否已提交取消
     */
    public record CancellationExecutionResult(String orderSn, Boolean cancelled) {
    }

    /**
     * @param requestId 幂等退款请求标识
     * @param orderSn 订单号
     * @param type 退款类型
     * @param refundAmount 实际退款金额
     * @param status 退款状态
     */
    public record RefundExecutionResult(
            String requestId,
            String orderSn,
            Integer type,
            Integer refundAmount,
            Integer status) {
    }
}
