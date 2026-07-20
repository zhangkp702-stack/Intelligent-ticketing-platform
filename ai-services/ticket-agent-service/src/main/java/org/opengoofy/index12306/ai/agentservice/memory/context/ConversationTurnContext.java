package org.opengoofy.index12306.ai.agentservice.memory.context;

import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;

import java.util.Objects;

/**
 * 最近上下文中的一轮完整用户问答。
 *
 * @param turnId 已完成轮次标识
 * @param userMessage 用户问题
 * @param assistantMessage 助手回答
 */
public record ConversationTurnContext(
        String turnId,
        AgentChatMessage userMessage,
        AgentChatMessage assistantMessage) {

    /**
     * 创建完整历史轮次。
     *
     * @param turnId 已完成轮次标识
     * @param userMessage 用户问题
     * @param assistantMessage 助手回答
     */
    public ConversationTurnContext {
        // 历史轮次必须同时拥有确定的用户问题和助手回答。
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(assistantMessage, "assistantMessage");
        if (userMessage.role() != MessageRole.USER) {
            throw new IllegalArgumentException("历史轮次用户消息角色必须为 USER");
        }
        if (assistantMessage.role() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("历史轮次助手消息角色必须为 ASSISTANT");
        }
    }

}
