package org.opengoofy.index12306.ai.agentservice.infra.enums;

/**
 * 单个角色与模型候选组合的熔断状态。
 */
public enum ModelCircuitState {

    /** 熔断器关闭，允许正常调用。 */
    CLOSED,
    /** 熔断器打开，暂时拒绝调用该候选模型。 */
    OPEN,
    /** 熔断器半开，允许有限探测调用。 */
    HALF_OPEN
}
