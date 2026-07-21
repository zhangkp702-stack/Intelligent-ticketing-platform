package org.opengoofy.index12306.ai.agentservice.chat.enums;

/**
 * 定义对话入口能够识别的稳定业务意图，供后续工作流选择和审计使用。
 */
public enum AgentIntent {
    GENERAL_CHAT,
    TRAIN_QUERY,
    TRAIN_STOP_QUERY,
    PASSENGER_QUERY,
    ORDER_QUERY,
    PAYMENT_QUERY,
    TICKET_PURCHASE,
    ORDER_CANCELLATION,
    TICKET_REFUND
}
