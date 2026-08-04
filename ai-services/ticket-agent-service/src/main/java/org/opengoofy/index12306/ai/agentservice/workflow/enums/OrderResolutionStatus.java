package org.opengoofy.index12306.ai.agentservice.workflow.enums;

/**
 * 取消订单工作流定位本人可取消订单后的稳定结果。
 */
public enum OrderResolutionStatus {
    /** 已唯一确定可取消订单。 */
    RESOLVED,
    /** 存在多个候选订单，需要用户选择。 */
    SELECTION_REQUIRED,
    /** 未找到当前用户可取消的订单。 */
    NO_CANCELLABLE_ORDERS
}
