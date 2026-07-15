package org.opengoofy.index12306.ai.agentservice.memory.domain;

/**
 * 本轮问题的主题路由决策。
 */
public enum RouteDecision {
    SELECT_EXISTING,
    CREATE_NEW,
    FALLBACK_ACTIVE
}
