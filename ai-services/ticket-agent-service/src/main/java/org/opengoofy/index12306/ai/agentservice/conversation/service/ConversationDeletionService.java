package org.opengoofy.index12306.ai.agentservice.conversation.service;

/**
 * 定义当前用户删除其会话及关联数据的能力。
 */
public interface ConversationDeletionService {

    /**
     * 删除当前用户拥有的指定会话及其关联数据。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     */
    void deleteConversation(String userId, String conversationId);
}
