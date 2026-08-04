package org.opengoofy.index12306.ai.agentservice.chat.enums;

/**
 * 定义对话入口能够识别的稳定业务意图，供后续工作流选择和审计使用。
 */
public enum AgentIntent {
    /** 普通问答，不调用业务查询链。 */
    GENERAL_CHAT,
    /** 查询车次或余票。 */
    TRAIN_QUERY,
    /** 查询指定车次的经停站。 */
    TRAIN_STOP_QUERY,
    /** 查询常用乘车人信息。 */
    PASSENGER_QUERY,
    /** 查询订单信息。 */
    ORDER_QUERY,
    /** 查询支付信息。 */
    PAYMENT_QUERY,
    /** 发起购票流程。 */
    TICKET_PURCHASE,
    /** 发起订单取消流程。 */
    ORDER_CANCELLATION,
    /** 发起退票流程。 */
    TICKET_REFUND
}
