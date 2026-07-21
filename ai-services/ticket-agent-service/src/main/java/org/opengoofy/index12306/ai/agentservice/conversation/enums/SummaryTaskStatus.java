package org.opengoofy.index12306.ai.agentservice.conversation.enums;

/**
 * 异步会话摘要任务状态。
 */
public enum SummaryTaskStatus {
    PENDING,
    PUBLISHED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED
}
