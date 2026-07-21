package org.opengoofy.index12306.ai.agentservice.workflow.enums;

/**
 * 退票工作流解析订单和可退车票后的服务端状态。
 */
public enum RefundResolutionStatus {
    RESOLVED,
    ORDER_SELECTION_REQUIRED,
    TICKET_SELECTION_REQUIRED,
    NO_REFUNDABLE_ORDERS,
    NO_REFUNDABLE_TICKETS
}
