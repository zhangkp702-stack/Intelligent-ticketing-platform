package org.opengoofy.index12306.ai.agentservice.action.enums;

/**
 * UNKNOWN 操作对账事件从事务内创建到消费完成的持久化状态。
 */
public enum ActionReconciliationStatus {
    PENDING,
    PUBLISHED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED
}
