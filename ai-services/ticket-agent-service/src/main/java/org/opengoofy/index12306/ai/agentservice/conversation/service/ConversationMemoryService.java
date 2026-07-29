package org.opengoofy.index12306.ai.agentservice.conversation.service;

import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;

/**
 * 定义会话、消息和问答轮次的一致性写入能力。
 */
public interface ConversationMemoryService {

    /**
     * 为用户创建新的活动会话。
     *
     * @param userId 用户标识
     * @param title 会话标题
     * @return 已持久化会话
     */
    ConversationEntity createConversation(String userId, String title);

    /**
     * 幂等创建或恢复用户提问轮次。
     *
     * @param command 用户问题写入命令
     * @return 启动轮次结果
     */
    StartedTurn startTurn(StartTurnCommand command);

    /**
     * 完成问答轮次并保存助手消息。
     *
     * @param command 助手回答完成命令
     * @return 已持久化助手消息
     */
    MessageEntity completeTurn(CompleteTurnCommand command);

    /**
     * 将运行中的轮次标记为失败。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @param failureCategory 稳定失败分类
     */
    void failTurn(String userId, String turnId, String failureCategory);

    /**
     * 查询当前用户轮次的幂等状态。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @return 轮次状态和已完成回答
     */
    TurnState getTurnState(String userId, String turnId);

    /**
     * 取消指定运行中轮次。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     */
    void cancelTurn(String userId, String turnId);

    /**
     * 按会话和请求标识取消运行中轮次。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param requestId 请求标识
     * @return 找到并取消轮次时返回 true
     */
    boolean cancelTurn(String userId, String conversationId, String requestId);

    /**
     * 用户问题写入命令。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param requestId 请求标识
     * @param idempotencyKey 客户端幂等键
     * @param content 用户问题
     * @param tokenCount 估算 Token 数
     */
    record StartTurnCommand(
            String userId,
            String conversationId,
            String requestId,
            String idempotencyKey,
            String content,
            int tokenCount) {
    }

    /**
     * 启动轮次返回结果。
     *
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param userMessageId 用户消息标识
     * @param sequenceNo 用户消息序号
     * @param created 是否由本次请求新建
     */
    record StartedTurn(
            String conversationId,
            String turnId,
            String userMessageId,
            long sequenceNo,
            boolean created) {
    }

    /**
     * 助手回答完成命令。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @param content 助手回答正文
     * @param tokenCount 估算 Token 数
     */
    record CompleteTurnCommand(String userId, String turnId, String content, int tokenCount) {
    }

    /**
     * 幂等轮次读取结果。
     *
     * @param status 当前轮次状态
     * @param assistantContent 已完成回答，未完成时为空
     */
    record TurnState(TurnStatus status, String assistantContent) {
    }
}
