package org.opengoofy.index12306.ai.agentservice.model.config;

/**
 * 模型候选项经过确认后可以提供的能力。
 */
public enum ModelCapability {

    CHAT,
    TOOL_CALLING,
    STRUCTURED_OUTPUT,
    STREAMING
}
