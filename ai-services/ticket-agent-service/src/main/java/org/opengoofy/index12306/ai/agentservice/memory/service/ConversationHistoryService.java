package org.opengoofy.index12306.ai.agentservice.memory.service;

import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ConversationPage;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ConversationView;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.HistoryMessagePage;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.HistoryMessageView;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageType;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.MessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 为前端提供经过用户归属校验和数量限制的会话、消息历史读取能力。
 */
@Service
public class ConversationHistoryService {

    private static final int MAX_CONVERSATION_PAGE_SIZE = 50;
    private static final int MAX_MESSAGE_PAGE_SIZE = 100;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    /**
     * 创建会话历史查询服务。
     *
     * @param conversationRepository 会话仓储
     * @param messageRepository 消息仓储
     */
    public ConversationHistoryService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 分页查询当前用户自己的会话，并按最近更新时间倒序排列。
     *
     * @param userId 当前用户标识
     * @param current 当前页码，从 1 开始
     * @param size 每页数量
     * @return 用户会话分页
     */
    @Transactional(readOnly = true)
    public ConversationPage listConversations(String userId, int current, int size) {
        requireUser(userId);
        int normalizedCurrent = positive(current, "页码必须大于 0");
        int normalizedSize = boundedSize(size, MAX_CONVERSATION_PAGE_SIZE);

        // 只按 userId 查询，避免先加载会话再在内存中过滤造成越权窗口。
        Page<ConversationEntity> page = conversationRepository.findByUserId(
                userId,
                PageRequest.of(
                        normalizedCurrent - 1,
                        normalizedSize,
                        Sort.by(
                                Sort.Order.desc("updatedAt"),
                                Sort.Order.desc("id"))));
        List<ConversationView> records = page.getContent().stream()
                .map(this::toConversationView)
                .toList();
        return new ConversationPage(
                normalizedCurrent, normalizedSize, page.getTotalElements(), records);
    }

    /**
     * 使用消息序号游标加载当前用户会话的最近一批文本消息。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @param beforeSequence 不包含的消息序号上界，首次查询允许为空
     * @param size 返回数量
     * @return 按序号升序排列的历史消息页
     */
    @Transactional(readOnly = true)
    public HistoryMessagePage listMessages(
            String userId,
            String conversationId,
            Long beforeSequence,
            int size) {
        ConversationEntity conversation = requireConversation(userId, conversationId);
        long upperBound = beforeSequence == null
                ? conversation.getLastMessageSequence() + 1
                : positiveSequence(beforeSequence);
        int normalizedSize = boundedSize(size, MAX_MESSAGE_PAGE_SIZE);

        // 多读取一条用于判断是否还有更早消息，工具调用等内部消息不会返回前端。
        List<MessageEntity> descending = messageRepository.findConversationHistory(
                conversationId,
                MessageType.TEXT,
                upperBound,
                PageRequest.of(0, normalizedSize + 1));
        boolean hasMore = descending.size() > normalizedSize;
        List<MessageEntity> selected = new ArrayList<>(
                descending.subList(0, Math.min(normalizedSize, descending.size())));
        Collections.reverse(selected);
        List<HistoryMessageView> messages = selected.stream()
                .map(this::toMessageView)
                .toList();
        Long nextBeforeSequence = hasMore && !selected.isEmpty()
                ? selected.get(0).getSequenceNo()
                : null;
        return new HistoryMessagePage(messages, nextBeforeSequence, hasMore);
    }

    /**
     * 读取并校验会话属于当前用户。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 当前用户会话
     */
    @Transactional(readOnly = true)
    public ConversationEntity requireConversation(String userId, String conversationId) {
        requireUser(userId);
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("会话标识不能为空");
        }

        // 会话不存在和不属于当前用户使用相同异常边界，避免通过标识探测其他用户数据。
        ConversationEntity conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        return conversation;
    }

    /**
     * 转换会话实体为前端白名单字段。
     *
     * @param conversation 会话实体
     * @return 会话视图
     */
    private ConversationView toConversationView(ConversationEntity conversation) {
        return new ConversationView(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getStatus(),
                conversation.getActiveTopicId(),
                conversation.getLastMessageSequence(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }

    /**
     * 转换原始文本消息为前端历史消息。
     *
     * @param message 消息实体
     * @return 历史消息视图
     */
    private HistoryMessageView toMessageView(MessageEntity message) {
        return new HistoryMessageView(
                message.getId(),
                message.getTurnId(),
                message.getTopicId(),
                message.getSequenceNo(),
                message.getRole(),
                message.getMessageType(),
                message.getContent(),
                message.getCreatedAt());
    }

    /**
     * 校验页码或其他正整数参数。
     *
     * @param value 原始数值
     * @param message 失败提示
     * @return 有效正整数
     */
    private int positive(int value, String message) {
        if (value <= 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /**
     * 校验并限制分页大小。
     *
     * @param value 原始分页大小
     * @param maximum 服务端上限
     * @return 有效分页大小
     */
    private int boundedSize(int value, int maximum) {
        int positiveValue = positive(value, "分页大小必须大于 0");
        return Math.min(positiveValue, maximum);
    }

    /**
     * 校验消息游标是有效正序号。
     *
     * @param value 消息序号
     * @return 有效游标
     */
    private long positiveSequence(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("消息游标必须大于 0");
        }
        return value;
    }

    /**
     * 校验用户标识不为空。
     *
     * @param userId 用户标识
     */
    private void requireUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("用户标识不能为空");
        }
    }
}
