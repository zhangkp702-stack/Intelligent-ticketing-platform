package org.opengoofy.index12306.ai.agentservice.action.enums;


/**
 * 操作草案从待确认到最终结果的持久化状态。
 */
public enum AgentActionStatus {
    /** 草案已生成，等待用户确认。 */
    AWAITING_CONFIRMATION,
    /** 用户已确认，等待执行器领取。 */
    QUEUED,
    /** 正在调用真实票务业务。 */
    EXECUTING,
    /** 真实业务操作已成功完成。 */
    SUCCEEDED,
    /** 真实业务操作已明确失败。 */
    FAILED,
    /** 真实业务操作结果暂时未知。 */
    UNKNOWN,
    /** 正在对未知结果进行下游对账。 */
    RECONCILING,
    /** 自动对账已达到上限，等待人工核验。 */
    MANUAL_REVIEW,
    /** 用户确认窗口已经过期。 */
    EXPIRED,
    /** 用户或系统已取消该草案。 */
    CANCELLED
}
