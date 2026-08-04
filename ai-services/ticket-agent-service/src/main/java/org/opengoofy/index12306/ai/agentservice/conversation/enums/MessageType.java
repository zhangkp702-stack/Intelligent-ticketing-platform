package org.opengoofy.index12306.ai.agentservice.conversation.enums;

/**
 * 对话消息业务类型。
 */
public enum MessageType {
    /** 普通文本消息。 */
    TEXT,
    /** 发起工具调用的结构化消息。 */
    TOOL_CALL,
    /** 工具返回结果的结构化消息。 */
    TOOL_RESULT,
    /** 服务端内部保存的结构化状态消息。 */
    STRUCTURED_STATE
}
