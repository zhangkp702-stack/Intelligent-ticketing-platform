package org.opengoofy.index12306.ai.agentservice.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.memory.config.AgentMemoryProperties;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ContextSnapshotEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationSummaryEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageType;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ContextSnapshotRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ConversationSummaryRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * 按会话唯一摘要和摘要边界后的原始消息装配回答上下文。
 */
@Service
public class ConversationContextService {

    private final AgentMemoryProperties properties;
    private final ConversationRepository conversationRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final MessageRepository messageRepository;
    private final ContextSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 创建会话级上下文装配服务。
     *
     * @param properties 上下文容量配置
     * @param conversationRepository 会话仓储
     * @param summaryRepository 会话摘要仓储
     * @param messageRepository 原始消息仓储
     * @param snapshotRepository 上下文快照仓储
     * @param objectMapper JSON 序列化器
     * @param clock 统一时钟
     */
    public ConversationContextService(
            AgentMemoryProperties properties,
            ConversationRepository conversationRepository,
            ConversationSummaryRepository summaryRepository,
            MessageRepository messageRepository,
            ContextSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.conversationRepository = conversationRepository;
        this.summaryRepository = summaryRepository;
        this.messageRepository = messageRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 加载会话唯一摘要及其边界后的最近消息，并保存本次实际使用的上下文快照。
     *
     * @param userId 当前用户标识
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @return 可直接用于回答模型的会话上下文
     */
    @Transactional
    public ConversationContext load(String userId, String requestId, String conversationId) {
        // 先校验会话归属，防止通过会话标识读取其他用户的摘要和消息。
        ConversationEntity conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }

        // 摘要缺失或异步处理滞后时，边界后的原始消息会自动补齐上下文。
        ConversationSummaryEntity summary = summaryRepository.findByConversationId(conversationId).orElse(null);
        long summarizedThrough = summary == null ? 0 : summary.getSummarizedThroughSequence();
        List<MessageEntity> recentDescending = messageRepository.findRecentConversationMessages(
                conversationId,
                summarizedThrough,
                PageRequest.of(0, properties.recentMessageLimit()));
        List<ContextMessage> selectedMessages = selectWithinTokenBudget(summary, recentDescending);
        ConversationContext context = new ConversationContext(
                conversationId,
                summary == null ? null : summary.getId(),
                summary == null ? null : summary.getSummaryContent(),
                summary == null ? null : summary.getStructuredState(),
                summary == null ? null : summary.getSummaryVersion(),
                summarizedThrough,
                selectedMessages,
                estimateContextTokens(summary, selectedMessages));

        // 快照只保存边界、引用和哈希，不复制用户正文。
        saveSnapshot(requestId, context);
        return context;
    }

    /**
     * 在 Token 预算内从最新消息向前选择连续上下文。
     *
     * @param summary 当前会话摘要
     * @param recentDescending 摘要边界后的消息倒序列表
     * @return 按消息序号升序排列的上下文消息
     */
    private List<ContextMessage> selectWithinTokenBudget(
            ConversationSummaryEntity summary,
            List<MessageEntity> recentDescending) {
        int consumedTokens = summary == null ? 0 : estimateTokens(summary.getSummaryContent());
        List<ContextMessage> selectedDescending = new ArrayList<>();

        // 一旦达到预算便停止向前扩展，保证保留下来的消息范围连续且偏向最新内容。
        for (MessageEntity message : recentDescending) {
            int messageTokens = normalizedTokenCount(message);
            if (consumedTokens + messageTokens > properties.contextTokenBudget()) {
                break;
            }
            selectedDescending.add(new ContextMessage(
                    message.getId(), message.getSequenceNo(), message.getRole(),
                    message.getMessageType(), message.getContent(), messageTokens));
            consumedTokens += messageTokens;
        }
        Collections.reverse(selectedDescending);
        return List.copyOf(selectedDescending);
    }

    /**
     * 保存本次模型输入使用的会话摘要版本和消息范围。
     *
     * @param requestId 请求标识
     * @param context 已装配上下文
     */
    private void saveSnapshot(String requestId, ConversationContext context) {
        if (snapshotRepository.findByRequestId(requestId).isPresent()) {
            return;
        }
        Long fromSequence = context.messages().isEmpty() ? null : context.messages().get(0).sequenceNo();
        Long throughSequence = context.messages().isEmpty()
                ? null : context.messages().get(context.messages().size() - 1).sequenceNo();
        List<String> messageIds = context.messages().stream().map(ContextMessage::messageId).toList();

        // 使用上下文哈希支持问题回放，同时避免在审计表中重复存储正文。
        ContextSnapshotEntity snapshot = ContextSnapshotEntity.create(
                requestId,
                context.conversationId(),
                context.summaryId(),
                context.summaryVersion(),
                context.summarizedThroughSequence(),
                fromSequence,
                throughSequence,
                writeJson(messageIds),
                context.estimatedTokenCount(),
                hashContext(context),
                clock.instant());
        snapshotRepository.save(snapshot);
    }

    /**
     * 取得持久化 Token 数，缺失时使用保守字符估算。
     *
     * @param message 原始消息
     * @return 非负 Token 估算
     */
    private int normalizedTokenCount(MessageEntity message) {
        return message.getTokenCount() > 0 ? message.getTokenCount() : estimateTokens(message.getContent());
    }

    /**
     * 使用字符长度估算 Token 数。
     *
     * @param content 文本内容
     * @return 估算 Token 数
     */
    private int estimateTokens(String content) {
        return content == null || content.isEmpty() ? 0 : Math.max(1, (content.length() + 1) / 2);
    }

    /**
     * 计算摘要与所选消息的总 Token 估算。
     *
     * @param summary 当前会话摘要
     * @param messages 所选消息
     * @return 总 Token 估算
     */
    private int estimateContextTokens(ConversationSummaryEntity summary, List<ContextMessage> messages) {
        int summaryTokens = summary == null ? 0 : estimateTokens(summary.getSummaryContent());
        return summaryTokens + messages.stream().mapToInt(ContextMessage::tokenCount).sum();
    }

    /**
     * 计算摘要和消息正文的 SHA-256 哈希。
     *
     * @param context 会话上下文
     * @return 十六进制哈希
     */
    private String hashContext(ConversationContext context) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (context.summaryContent() != null) {
                digest.update(context.summaryContent().getBytes(StandardCharsets.UTF_8));
            }
            for (ContextMessage message : context.messages()) {
                digest.update(message.role().name().getBytes(StandardCharsets.UTF_8));
                digest.update(message.content().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少 SHA-256", ex);
        }
    }

    /**
     * 将快照中的消息标识列表转换为 JSON。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("上下文快照 JSON 序列化失败", ex);
        }
    }

    /**
     * 会话摘要和摘要边界后消息组成的回答上下文。
     *
     * @param conversationId 会话标识
     * @param summaryId 摘要标识
     * @param summaryContent 完整会话摘要
     * @param structuredState 结构化业务状态
     * @param summaryVersion 摘要版本
     * @param summarizedThroughSequence 摘要边界
     * @param messages 最近原始消息
     * @param estimatedTokenCount 总 Token 估算
     */
    public record ConversationContext(
            String conversationId,
            String summaryId,
            String summaryContent,
            String structuredState,
            Integer summaryVersion,
            long summarizedThroughSequence,
            List<ContextMessage> messages,
            int estimatedTokenCount) {
    }

    /**
     * 回答上下文中的一条原始消息。
     *
     * @param messageId 消息标识
     * @param sequenceNo 消息序号
     * @param role 消息角色
     * @param messageType 消息类型
     * @param content 消息正文
     * @param tokenCount Token 估算
     */
    public record ContextMessage(
            String messageId,
            long sequenceNo,
            MessageRole role,
            MessageType messageType,
            String content,
            int tokenCount) {
    }
}
