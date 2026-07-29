package org.opengoofy.index12306.ai.agentservice.conversation.service;

import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ConversationPage;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.HistoryMessagePage;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;

/**
 * 定义经过用户归属校验的会话和消息历史查询能力。
 */
public interface ConversationHistoryService {

    /**
     * 分页查询当前用户自己的会话。
     *
     * @param userId 当前用户标识
     * @param beforeSequence 不包含的消息序号上界，首次查询允许为空
     * @param size 每页数量
     * @return 用户会话分页
     */
    ConversationPage listConversations(String userId, int current, int size);

    /**
     * 分页查询当前用户指定会话的历史消息。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @param current 当前页码
     * @param size 每页数量
     * @return 历史消息分页
     */
    HistoryMessagePage listMessages(
            String userId,
            String conversationId,
            Long beforeSequence,
            int size);

    /**
     * 查询并校验会话归属。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 当前用户拥有的会话
     */
    ConversationEntity requireConversation(String userId, String conversationId);
}
