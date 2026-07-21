package org.opengoofy.index12306.ai.agentservice.conversation.service;

import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageType;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.TurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 负责会话、消息和问答轮次的一致性写入，并合并异步摘要目标。
 */
@Service
public class ConversationMemoryService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final TurnRepository turnRepository;
    private final Clock clock;
    private final SummaryTaskService summaryTaskService;

    /**
     * 创建会话记忆写入服务。
     *
     * @param conversationRepository 会话仓储
     * @param messageRepository 消息仓储
     * @param turnRepository 轮次仓储
     * @param clock 统一时钟
     * @param summaryTaskService 会话摘要任务服务
     */
    public ConversationMemoryService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            TurnRepository turnRepository,
            Clock clock,
            SummaryTaskService summaryTaskService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.turnRepository = turnRepository;
        this.clock = clock;
        this.summaryTaskService = summaryTaskService;
    }

    /**
     * 为用户创建一个新的活动会话。
     *
     * @param userId 用户标识
     * @param title 会话标题
     * @return 已持久化会话
     */
    @Transactional
    public ConversationEntity createConversation(String userId, String title) {
        requireText(userId, "用户标识不能为空");
        Instant now = clock.instant();

        // 会话本身不携带模型或票务业务状态，摘要由后续异步任务维护。
        ConversationEntity conversation = ConversationEntity.create(userId, title, now);
        return conversationRepository.save(conversation);
    }

    /**
     * 幂等写入当前用户问题并创建等待回答的运行中轮次。
     *
     * @param command 用户问题写入命令
     * @return 新建或已存在的轮次和用户消息
     */
    @Transactional
    public StartedTurn startTurn(StartTurnCommand command) {
        validateStartCommand(command);

        // 相同请求 ID 的网络重试直接返回原轮次，不重复分配消息序号。
        TurnEntity existingTurn = turnRepository
                .findByConversationIdAndRequestId(command.conversationId(), command.requestId())
                .orElse(null);
        if (existingTurn != null) {
            ConversationEntity conversation = requireConversation(command.userId(), command.conversationId());
            MessageEntity message = requireMessage(existingTurn.getUserMessageId());
            return toStartedTurn(conversation, existingTurn, message, false);
        }

        // 锁定会话后分配严格递增序号，并在同一事务中保存消息与轮次。
        ConversationEntity conversation = requireLockedConversation(command.userId(), command.conversationId());
        TurnEntity concurrentExistingTurn = turnRepository
                .findByConversationIdAndRequestId(command.conversationId(), command.requestId())
                .orElse(null);
        if (concurrentExistingTurn != null) {
            // 会话锁内再次检查，确保并发重试返回首个事务已经创建的轮次。
            MessageEntity existingMessage = requireMessage(concurrentExistingTurn.getUserMessageId());
            return toStartedTurn(conversation, concurrentExistingTurn, existingMessage, false);
        }
        if (StringUtils.hasText(command.idempotencyKey())) {
            MessageEntity existingMessage = messageRepository
                    .findByConversationIdAndIdempotencyKey(command.conversationId(), command.idempotencyKey())
                    .orElse(null);
            if (existingMessage != null && existingMessage.getTurnId() != null) {
                TurnEntity idempotentTurn = turnRepository.findById(existingMessage.getTurnId())
                        .orElseThrow(() -> new IllegalStateException("幂等消息缺少关联轮次"));
                return toStartedTurn(conversation, idempotentTurn, existingMessage, false);
            }
        }

        Instant now = clock.instant();
        long sequence = conversation.nextMessageSequence(now);
        MessageEntity userMessage = MessageEntity.create(
                command.conversationId(),
                sequence,
                MessageRole.USER,
                MessageType.TEXT,
                command.content(),
                "text/plain",
                command.tokenCount(),
                command.requestId(),
                command.idempotencyKey(),
                now);
        TurnEntity turn = TurnEntity.start(
                command.conversationId(), command.requestId(), userMessage.getId(), now);
        userMessage.attachTurn(turn.getId(), now);
        messageRepository.save(userMessage);
        turnRepository.save(turn);
        return toStartedTurn(conversation, turn, userMessage, true);
    }

    /**
     * 追加最终助手回答并原子完成本轮问答。
     *
     * @param command 助手回答写入命令
     * @return 新建或已存在的助手消息
     */
    @Transactional
    public MessageEntity completeTurn(CompleteTurnCommand command) {
        requireText(command.userId(), "用户标识不能为空");
        requireText(command.content(), "助手回答不能为空");
        TurnEntity turn = turnRepository.findLockedById(command.turnId())
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));

        // 已完成轮次直接返回原助手消息，保证客户端确认重试不重复写消息。
        if (turn.getStatus() == TurnStatus.COMPLETED) {
            return requireMessage(turn.getAssistantMessageId());
        }

        ConversationEntity conversation = requireLockedConversation(command.userId(), turn.getConversationId());

        // 助手消息与轮次完成状态在同一事务提交，失败时一起回滚。
        Instant now = clock.instant();
        long sequence = conversation.nextMessageSequence(now);
        MessageEntity assistantMessage = MessageEntity.create(
                turn.getConversationId(),
                sequence,
                MessageRole.ASSISTANT,
                MessageType.TEXT,
                command.content(),
                "text/plain",
                command.tokenCount(),
                turn.getRequestId(),
                turn.getRequestId() + ":assistant",
                now);
        assistantMessage.attachTurn(turn.getId(), now);
        messageRepository.save(assistantMessage);
        turn.complete(assistantMessage.getId(), now);
        // 与回答在同一事务内仅合并任务目标，MQ 发布和模型摘要在事务提交后异步执行。
        summaryTaskService.requestIfNeeded(turn.getConversationId(), sequence);
        return assistantMessage;
    }

    /**
     * 在没有生成最终助手消息时记录轮次失败。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @param failureCategory 稳定失败分类
     */
    @Transactional
    public void failTurn(String userId, String turnId, String failureCategory) {
        TurnEntity turn = turnRepository.findLockedById(turnId)
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));

        // 失败状态更新前再次校验会话所有权，避免跨用户操作轮次。
        requireConversation(userId, turn.getConversationId());
        turn.fail(failureCategory, clock.instant());
    }

    /**
     * 读取幂等轮次的当前状态和已完成回答，用于决定是否可以安全复用结果。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @return 轮次状态和已完成回答
     */
    @Transactional(readOnly = true)
    public TurnState getTurnState(String userId, String turnId) {
        TurnEntity turn = turnRepository.findById(turnId)
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));

        // 先校验会话所有权，再按终态读取助手消息，避免幂等查询越权。
        requireConversation(userId, turn.getConversationId());
        String assistantContent = turn.getAssistantMessageId() == null
                ? null : requireMessage(turn.getAssistantMessageId()).getContent();
        return new TurnState(turn.getStatus(), assistantContent);
    }

    /**
     * 将客户端已中止且仍在运行的轮次标记为取消。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     */
    @Transactional
    public void cancelTurn(String userId, String turnId) {
        TurnEntity turn = turnRepository.findLockedById(turnId)
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));

        // 终态轮次保持原结果，避免取消回调覆盖已经持久化的完成或失败状态。
        requireConversation(userId, turn.getConversationId());
        if (turn.getStatus() == TurnStatus.RUNNING) {
            turn.cancel(clock.instant());
        }
    }

    /**
     * 按会话和请求标识取消仍在执行的轮次，并校验当前用户拥有该会话。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @param requestId 请求标识
     * @return 找到且成功取消运行中轮次时返回 true
     */
    @Transactional
    public boolean cancelTurn(String userId, String conversationId, String requestId) {
        // 先锁定并校验会话归属，防止通过请求标识取消其他用户的轮次。
        requireLockedConversation(userId, conversationId);
        TurnEntity turn = turnRepository.findByConversationIdAndRequestId(conversationId, requestId)
                .orElse(null);
        if (turn == null) {
            return false;
        }

        // 使用轮次行锁与正常完成流程互斥，只取消仍处于运行状态的轮次。
        TurnEntity lockedTurn = turnRepository.findLockedById(turn.getId())
                .orElseThrow(() -> new IllegalStateException("轮次不存在"));
        if (lockedTurn.getStatus() != TurnStatus.RUNNING) {
            return false;
        }
        lockedTurn.cancel(clock.instant());
        return true;
    }

    /**
     * 锁定并校验会话所有权。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @return 锁定的会话实体
     */
    private ConversationEntity requireLockedConversation(String userId, String conversationId) {
        ConversationEntity conversation = conversationRepository.findLockedById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        assertOwner(conversation, userId);
        return conversation;
    }

    /**
     * 读取并校验会话所有权。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @return 会话实体
     */
    private ConversationEntity requireConversation(String userId, String conversationId) {
        ConversationEntity conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        assertOwner(conversation, userId);
        return conversation;
    }

    /**
     * 校验会话属于当前用户。
     *
     * @param conversation 会话实体
     * @param userId 当前用户标识
     */
    private void assertOwner(ConversationEntity conversation, String userId) {
        requireText(userId, "用户标识不能为空");
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
    }

    /**
     * 读取必须存在的消息。
     *
     * @param messageId 消息标识
     * @return 消息实体
     */
    private MessageEntity requireMessage(String messageId) {
        return messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalStateException("轮次关联消息不存在"));
    }

    /**
     * 校验用户问题命令的必填字段。
     *
     * @param command 用户问题命令
     */
    private void validateStartCommand(StartTurnCommand command) {
        Objects.requireNonNull(command, "command");
        requireText(command.userId(), "用户标识不能为空");
        requireText(command.conversationId(), "会话标识不能为空");
        requireText(command.requestId(), "请求标识不能为空");
        requireText(command.content(), "用户问题不能为空");
    }

    /**
     * 校验文本字段不为空。
     *
     * @param value 字段值
     * @param message 失败说明
     */
    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 转换轮次和用户消息为稳定返回结果。
     *
     * @param conversation 会话实体
     * @param turn 轮次实体
     * @param message 用户消息实体
     * @param created 是否本次新建
     * @return 启动轮次结果
     */
    private StartedTurn toStartedTurn(
            ConversationEntity conversation,
            TurnEntity turn,
            MessageEntity message,
            boolean created) {
        return new StartedTurn(
                conversation.getId(), turn.getId(), message.getId(),
                message.getSequenceNo(), created);
    }

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
    public record StartTurnCommand(
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
    public record StartedTurn(
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
    public record CompleteTurnCommand(String userId, String turnId, String content, int tokenCount) {
    }

    /**
     * 幂等轮次读取结果。
     *
     * @param status 当前轮次状态
     * @param assistantContent 已完成回答，未完成时为空
     */
    public record TurnState(TurnStatus status, String assistantContent) {
    }
}
