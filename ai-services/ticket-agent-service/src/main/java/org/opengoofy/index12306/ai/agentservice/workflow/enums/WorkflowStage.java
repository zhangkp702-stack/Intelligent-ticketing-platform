package org.opengoofy.index12306.ai.agentservice.workflow.enums;

/**
 * 购票工作流由服务端推进的阶段，模型不能直接跳过选择和确认步骤。
 */
public enum WorkflowStage {
    COLLECTING_TRIP,
    SELECTING_TRAIN,
    SELECTING_PASSENGERS,
    SELECTING_SEAT_CLASS,
    CREATING_DRAFT,
    COMPLETED,
    EXPIRED
}
