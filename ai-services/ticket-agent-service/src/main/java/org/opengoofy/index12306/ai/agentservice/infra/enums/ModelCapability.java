package org.opengoofy.index12306.ai.agentservice.infra.enums;

/**
 * 模型候选项经过确认后可以提供的能力。
 */
public enum ModelCapability {

    /** 支持普通文本对话。 */
    CHAT,
    /** 支持由模型发起工具调用。 */
    TOOL_CALLING,
    /** 支持输出可校验的结构化数据。 */
    STRUCTURED_OUTPUT,
    /** 支持以流式方式返回响应。 */
    STREAMING
}
