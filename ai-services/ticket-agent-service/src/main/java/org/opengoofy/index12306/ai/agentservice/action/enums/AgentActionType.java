package org.opengoofy.index12306.ai.agentservice.action.enums;


/**
 * 需要用户显式确认的高风险操作类型。
 */
public enum AgentActionType {
    /** 创建车票订单。 */
    TICKET_PURCHASE,
    /** 取消尚未支付或可取消的订单。 */
    TICKET_CANCEL,
    /** 退还已购车票。 */
    TICKET_REFUND
}
