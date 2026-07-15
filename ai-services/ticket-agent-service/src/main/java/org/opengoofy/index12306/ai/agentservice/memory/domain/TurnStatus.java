package org.opengoofy.index12306.ai.agentservice.memory.domain;

/**
 * 单轮问答执行状态。
 */
public enum TurnStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
