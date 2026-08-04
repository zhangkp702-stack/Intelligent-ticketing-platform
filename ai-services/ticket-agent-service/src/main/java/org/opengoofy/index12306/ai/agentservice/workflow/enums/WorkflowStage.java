package org.opengoofy.index12306.ai.agentservice.workflow.enums;

/**
 * 业务工作流由服务端推进的阶段，模型不能直接跳过选择和确认步骤。
 */
public enum WorkflowStage {
    /** 正在收集出发地、到达地和日期等行程信息。 */
    COLLECTING_TRIP,
    /** 等待或正在确定目标车次。 */
    SELECTING_TRAIN,
    /** 等待用户选择乘车人。 */
    SELECTING_PASSENGERS,
    /** 等待或正在确定席别。 */
    SELECTING_SEAT_CLASS,
    /** 等待用户选择待取消订单。 */
    SELECTING_ORDER,
    /** 等待用户选择待退订单。 */
    SELECTING_REFUND_ORDER,
    /** 等待用户选择待退车票。 */
    SELECTING_REFUND_TICKETS,
    /** 已具备业务信息，正在生成待确认草案。 */
    CREATING_DRAFT,
    /** 工作流已成功结束。 */
    COMPLETED,
    /** 工作流等待输入或确认超时。 */
    EXPIRED
}
