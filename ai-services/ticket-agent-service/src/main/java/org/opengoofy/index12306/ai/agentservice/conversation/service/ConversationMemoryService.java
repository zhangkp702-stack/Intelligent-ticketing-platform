package org.opengoofy.index12306.ai.agentservice.conversation.service;

import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;

import java.time.Instant;
import java.util.List;

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
     * 为当前用户在指定会话中预创建尚未执行的服务端轮次。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @return 服务端轮次、提交令牌和首次提交截止时间
     */
    PreparedTurn prepareTurn(String userId, String conversationId);

    /**
     * 幂等创建或恢复用户提问轮次。
     *
     * @param command 用户问题写入命令
     * @return 启动轮次结果
     */
    StartedTurn startTurn(StartTurnCommand command);

    /**
     * 在当前执行权仍有效时保存问题重写和拆分的持久化副本。
     *
     * @param userId 当前用户标识
     * @param turnId 当前轮次标识
     * @param executionOwner 当前执行实例标识
     * @param fencingToken 当前执行围栏令牌
     * @param hasRewrite 是否使用了不同于原文的独立问题
     * @param questionResolutionJson 已通过服务端校验的问题解析 JSON
     */
    void recordQuestionResolution(
            String userId,
            String turnId,
            String executionOwner,
            long fencingToken,
            boolean hasRewrite,
            String questionResolutionJson);

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
    void failTurn(
            String userId,
            String turnId,
            String executionOwner,
            long fencingToken,
            String failureCategory);

    /**
     * 为当前执行者续租运行中轮次，并检测取消或接管造成的执行权丢失。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @param executionOwner 执行实例标识
     * @param fencingToken 当前 fencing token
     * @return 当前执行权有效并成功续租时返回 true
     */
    boolean heartbeatTurn(
            String userId,
            String turnId,
            String executionOwner,
            long fencingToken);

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
     * @return 本次是否把运行中轮次推进为取消状态
     */
    boolean cancelTurn(String userId, String turnId);

    /**
     * 仅在执行权仍属于调用者时取消运行中轮次。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @param executionOwner 执行实例标识
     * @param fencingToken 当前 fencing token
     * @return 本次是否由当前执行者推进为取消状态
     */
    boolean cancelOwnedTurn(
            String userId,
            String turnId,
            String executionOwner,
            long fencingToken);

    /**
     * 查询已经失去在线执行租约、可由任一实例尝试接管的轮次。
     *
     * @return 最多一批只包含恢复所需字段的轮次快照
     */
    List<ExpiredTurnCandidate> findExpiredTurnCandidates();

    /**
     * 服务端预创建轮次结果。
     *
     * @param conversationId 会话标识
     * @param turnId 服务端轮次标识
     * @param submissionToken 首次提交 HMAC 令牌
     * @param expiresAt 首次提交截止时间
     */
    record PreparedTurn(
            String conversationId,
            String turnId,
            String submissionToken,
            Instant expiresAt) {
    }

    /**
     * 用户问题写入命令。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param turnId 服务端预创建轮次标识
     * @param submissionToken 服务端签发的首次提交令牌
     * @param username 下游用户归属查询所需用户名
     * @param content 用户问题
     * @param tokenCount 估算 Token 数
     */
    record StartTurnCommand(
            String userId,
            String conversationId,
            String turnId,
            String submissionToken,
            String username,
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
     * @param executionOwner 当前执行实例标识
     * @param fencingToken 当前轮次 fencing token
     */
    record StartedTurn(
            String conversationId,
            String turnId,
            String userMessageId,
            long sequenceNo,
            boolean created,
            String executionOwner,
            long fencingToken) {
    }

    /**
     * 助手回答完成命令。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @param content 助手回答正文
     * @param tokenCount 估算 Token 数
     * @param executionOwner 执行实例标识
     * @param fencingToken 领取时获得的 fencing token
     */
    record CompleteTurnCommand(
            String userId,
            String turnId,
            String content,
            int tokenCount,
            String executionOwner,
            long fencingToken) {
    }

    /**
     * 幂等轮次读取结果。
     *
     * @param turnId 轮次标识
     * @param conversationId 会话标识
     * @param status 当前轮次状态
     * @param assistantContent 已完成回答，未完成时为空
     * @param failureCategory 稳定失败分类，未失败时为空
     * @param startedAt 开始执行时间，尚未提交时为空
     * @param finishedAt 终态时间，尚未结束时为空
     */
    record TurnState(
            String turnId,
            String conversationId,
            TurnStatus status,
            String assistantContent,
            String failureCategory,
            Instant startedAt,
            Instant finishedAt) {
    }

    /**
     * 后台恢复运行中轮次所需的最小可信快照。
     *
     * @param userId 会话所属用户标识
     * @param username 首次提交时固化的用户名
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param content 首次提交时固化的用户问题
     */
    record ExpiredTurnCandidate(
            String userId,
            String username,
            String conversationId,
            String turnId,
            String content) {
    }
}
