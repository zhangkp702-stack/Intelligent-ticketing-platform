package org.opengoofy.index12306.ai.agentservice.memory.domain;

/**
 * 异步主题摘要任务状态。
 */
public enum SummaryTaskStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
