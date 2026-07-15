package org.opengoofy.index12306.ai.agentservice.model.routing;

/**
 * 单个角色与模型候选组合的熔断状态。
 */
public enum ModelCircuitState {

    CLOSED,
    OPEN,
    HALF_OPEN
}
