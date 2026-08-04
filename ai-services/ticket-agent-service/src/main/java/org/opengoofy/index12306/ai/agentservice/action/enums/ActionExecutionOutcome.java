package org.opengoofy.index12306.ai.agentservice.action.enums;


/**
 * 单次真实业务写调用的审计结果。
 */
public enum ActionExecutionOutcome {
    QUEUED,
    STARTED,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    RECONCILING
}
