package org.opengoofy.index12306.ai.agentservice.infra.enums;

/**
 * 单次候选模型调用的结果状态。
 */
public enum ModelAttemptOutcome {

    /** 当前候选模型调用成功。 */
    SUCCESS,
    /** 当前候选模型调用失败。 */
    FAILURE
}
