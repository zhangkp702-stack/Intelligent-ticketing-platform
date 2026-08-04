package org.opengoofy.index12306.ai.agentservice.workflow.enums;

/**
 * 退票工作流解析订单和可退车票后的服务端状态。
 */
public enum RefundResolutionStatus {
    /** 已唯一确定订单和退票范围。 */
    RESOLVED,
    /** 存在多个可退订单，需要用户选择订单。 */
    ORDER_SELECTION_REQUIRED,
    /** 存在多个可退车票，需要用户选择车票。 */
    TICKET_SELECTION_REQUIRED,
    /** 未找到可退订单。 */
    NO_REFUNDABLE_ORDERS,
    /** 订单中没有可退车票。 */
    NO_REFUNDABLE_TICKETS
}
