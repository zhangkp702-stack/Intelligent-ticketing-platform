package org.opengoofy.index12306.ai.agentservice.conversation.enums;

/**
 * 智能体会话生命周期状态。
 */
public enum ConversationStatus {
    /** 会话可继续接收新问题。 */
    ACTIVE,
    /** 会话已关闭，不再接收新问题。 */
    CLOSED,
    /** 会话已归档，仅供历史查阅。 */
    ARCHIVED
}
