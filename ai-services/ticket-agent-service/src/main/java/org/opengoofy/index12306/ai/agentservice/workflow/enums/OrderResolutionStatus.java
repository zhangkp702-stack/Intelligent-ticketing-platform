package org.opengoofy.index12306.ai.agentservice.workflow.enums;

/**
 * 取消订单工作流定位本人可取消订单后的稳定结果。
 */
public enum OrderResolutionStatus {
    RESOLVED,
    SELECTION_REQUIRED,
    NO_CANCELLABLE_ORDERS
}
