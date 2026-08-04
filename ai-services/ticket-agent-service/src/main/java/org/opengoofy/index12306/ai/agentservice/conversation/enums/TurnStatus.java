package org.opengoofy.index12306.ai.agentservice.conversation.enums;

/**
 * 单轮问答执行状态。
 */
public enum TurnStatus {
    /** 已预创建，尚未绑定用户问题。 */
    DRAFT,
    /** 已绑定问题，正在执行回答流程。 */
    RUNNING,
    /** 已成功保存最终回答。 */
    COMPLETED,
    /** 回答流程已失败结束。 */
    FAILED,
    /** 用户或系统已取消当前轮次。 */
    CANCELLED
}
