package org.opengoofy.index12306.ai.agentservice.action.domain;

/**
 * 单次真实业务写调用的审计结果。
 */
public enum ActionExecutionOutcome {
    STARTED,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
