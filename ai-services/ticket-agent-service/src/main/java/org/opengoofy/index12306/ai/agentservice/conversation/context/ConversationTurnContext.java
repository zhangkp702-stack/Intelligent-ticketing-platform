package org.opengoofy.index12306.ai.agentservice.conversation.context;

import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;

import java.util.Objects;

/**
 * 最近上下文中的一轮用户问答；失败轮次只保留用户问题。
 *
 * @param turnId 已完成轮次标识
 * @param userMessage 用户问题
 * @param assistantMessage 助手回答；模型失败或取消时为空
 */
public record ConversationTurnContext(
        String turnId,
        AgentChatMessage userMessage,
        AgentChatMessage assistantMessage) {

    /**
     * 创建历史轮次。
     *
     * @param turnId 已完成轮次标识
     * @param userMessage 用户问题
     * @param assistantMessage 助手回答；失败或取消时为空
     */
    public ConversationTurnContext {
        // 用户问题始终存在；失败轮次没有助手回答时必须保留该问题，避免它从上下文中静默丢失。
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(userMessage, "userMessage");
        if (userMessage.role() != MessageRole.USER) {
            throw new IllegalArgumentException("历史轮次用户消息角色必须为 USER");
        }
        if (assistantMessage != null && assistantMessage.role() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("历史轮次助手消息角色必须为 ASSISTANT");
        }
    }

}
