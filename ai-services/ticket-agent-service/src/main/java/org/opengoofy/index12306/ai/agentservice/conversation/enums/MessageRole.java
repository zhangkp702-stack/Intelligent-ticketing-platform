package org.opengoofy.index12306.ai.agentservice.conversation.enums;

/**
 * 对话消息角色。
 */
public enum MessageRole {
    /** 系统预设或系统级上下文消息。 */
    SYSTEM,
    /** 终端用户发送的消息。 */
    USER,
    /** 智能体生成的回答消息。 */
    ASSISTANT,
    /** 工具调用或工具返回相关消息。 */
    TOOL
}
