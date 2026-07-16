package org.opengoofy.index12306.ai.agentservice.action.domain;

/**
 * 操作草案从待确认到最终结果的持久化状态。
 */
public enum AgentActionStatus {
    AWAITING_CONFIRMATION,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    EXPIRED,
    CANCELLED
}
