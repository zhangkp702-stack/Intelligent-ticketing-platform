package org.opengoofy.index12306.ai.agentservice.conversation.enums;

/**
 * 异步会话摘要任务状态。
 */
public enum SummaryTaskStatus {
    /** 摘要任务已创建，等待发布。 */
    PENDING,
    /** 摘要任务已发布到异步消费端。 */
    PUBLISHED,
    /** 摘要任务正在执行。 */
    RUNNING,
    /** 摘要任务失败后等待重试。 */
    RETRY_WAIT,
    /** 摘要任务已成功完成。 */
    SUCCEEDED,
    /** 摘要任务已明确失败或耗尽重试。 */
    FAILED
}
