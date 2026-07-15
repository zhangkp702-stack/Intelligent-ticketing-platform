package org.opengoofy.index12306.ai.agentservice.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.memory.config.AgentMemoryProperties;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ContextRouteLogEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ContextSnapshotEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MemorySummaryEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MemorySummaryStatus;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.opengoofy.index12306.ai.agentservice.memory.domain.RouteDecision;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TopicEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TopicStatus;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ContextRouteLogRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ContextSnapshotRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.MemorySummaryRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.TopicRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 构建主题路由输入，并在选定主题后按预算装配可追溯上下文。
 */
@Service
public class TopicContextService {

    private final AgentMemoryProperties properties;
    private final ConversationRepository conversationRepository;
    private final TopicRepository topicRepository;
    private final MessageRepository messageRepository;
    private final MemorySummaryRepository summaryRepository;
    private final ContextSnapshotRepository snapshotRepository;
    private final ContextRouteLogRepository routeLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 创建主题上下文查询服务。
     *
     * @param properties 记忆容量配置
     * @param conversationRepository 会话仓储
     * @param topicRepository 主题仓储
     * @param messageRepository 消息仓储
     * @param summaryRepository 摘要仓储
     * @param snapshotRepository 上下文快照仓储
     * @param routeLogRepository 主题路由审计仓储
     * @param objectMapper JSON 序列化器
     * @param clock 统一时钟
     */
    public TopicContextService(
            AgentMemoryProperties properties,
            ConversationRepository conversationRepository,
            TopicRepository topicRepository,
            MessageRepository messageRepository,
            MemorySummaryRepository summaryRepository,
            ContextSnapshotRepository snapshotRepository,
            ContextRouteLogRepository routeLogRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.conversationRepository = conversationRepository;
        this.topicRepository = topicRepository;
        this.messageRepository = messageRepository;
        this.summaryRepository = summaryRepository;
        this.snapshotRepository = snapshotRepository;
        this.routeLogRepository = routeLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 构建“当前问题 + 最近用户问题 + 多个主题摘要卡片”的主题判断输入。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param currentMessageId 当前用户消息标识
     * @return 不包含历史助手回答的主题路由输入
     */
    @Transactional(readOnly = true)
    public TopicRouteInput buildRouteInput(String userId, String conversationId, String currentMessageId) {
        ConversationEntity conversation = requireOwnedConversation(userId, conversationId);
        MessageEntity currentMessage = messageRepository.findById(currentMessageId)
                .filter(message -> message.getConversationId().equals(conversationId))
                .filter(message -> message.getRole() == MessageRole.USER)
                .orElseThrow(() -> new IllegalArgumentException("当前用户消息不存在"));

        // 主题候选只取最近活跃主题的短摘要和结构化状态，不加载完整历史正文。
        List<TopicSummaryCard> topicCards = topicRepository
                .findByConversationIdAndStatusOrderByLastActiveAtDesc(
                        conversationId,
                        TopicStatus.ACTIVE,
                        PageRequest.of(0, properties.topicCandidateLimit()))
                .stream()
                .map(this::toTopicCard)
                .toList();

        // 最近问题查询只允许 USER 角色，明确排除助手回答和当前问题本身。
        List<UserQuestion> recentQuestions = new ArrayList<>(messageRepository
                .findRecentByRole(
                        conversationId,
                        MessageRole.USER,
                        currentMessageId,
                        PageRequest.of(0, properties.recentUserQuestionLimit()))
                .stream()
                .map(message -> new UserQuestion(
                        message.getId(), message.getContent(), message.getSequenceNo()))
                .toList());
        Collections.reverse(recentQuestions);

        return new TopicRouteInput(
                conversation.getId(),
                conversation.getActiveTopicId(),
                currentMessage.getId(),
                currentMessage.getContent(),
                List.copyOf(recentQuestions),
                topicCards);
    }

    /**
     * 加载选中主题的最新完整摘要和预算内最近消息，并保存上下文元数据快照。
     *
     * @param userId 用户标识
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param topicId 选中主题标识
     * @return 可直接交给后续编排层的主题上下文
     */
    @Transactional
    public TopicContext loadTopicContext(
            String userId,
            String requestId,
            String conversationId,
            String topicId) {
        requireOwnedConversation(userId, conversationId);
        TopicEntity topic = topicRepository.findByIdAndConversationId(topicId, conversationId)
                .orElseThrow(() -> new IllegalArgumentException("主题不存在或不属于当前会话"));
        MemorySummaryEntity summary = summaryRepository
                .findFirstByTopicIdAndStatusOrderByVersionNoDesc(topicId, MemorySummaryStatus.ACTIVE)
                .orElse(null);

        // 只查询摘要边界之后最近的有限消息，数据库读取本身就有数量上限。
        long summarizedThrough = summary == null ? 0 : summary.getThroughSequence();
        List<MessageEntity> recentDescending = messageRepository.findRecentTopicMessages(
                topicId,
                summarizedThrough,
                PageRequest.of(0, properties.recentMessageLimit()));
        List<ContextMessage> selectedMessages = selectWithinTokenBudget(summary, recentDescending);
        TopicContext context = new TopicContext(
                conversationId,
                topicId,
                topic.getStructuredState(),
                summary == null ? null : summary.getId(),
                summary == null ? null : summary.getSummaryContent(),
                selectedMessages,
                estimateContextTokens(summary, selectedMessages));

        // 快照记录实际使用的摘要、消息范围和内容哈希，便于问题回放但不复制正文。
        saveSnapshot(requestId, context);
        return context;
    }

    /**
     * 保存主题路由候选、最终决策和置信度审计。
     *
     * @param record 路由审计命令
     * @return 已保存路由记录标识
     */
    @Transactional
    public String recordRouteDecision(RouteDecisionRecord record) {
        requireOwnedConversation(record.userId(), record.conversationId());

        // 同一个请求的路由结果只落库一次，客户端重试直接复用首次审计记录。
        ContextRouteLogEntity existingRouteLog = routeLogRepository.findByRequestId(record.requestId())
                .orElse(null);
        if (existingRouteLog != null) {
            return existingRouteLog.getId();
        }
        Set<String> candidateSet = Set.copyOf(record.candidateTopicIds());
        if (record.decision() == RouteDecision.SELECT_EXISTING
                && !candidateSet.contains(record.selectedTopicId())) {
            throw new IllegalArgumentException("选中主题不在候选集合中");
        }

        // 候选主题以 JSON 数组保存，避免逗号拼接造成标识歧义。
        ContextRouteLogEntity routeLog = ContextRouteLogEntity.create(
                record.requestId(),
                record.conversationId(),
                record.currentMessageId(),
                writeJson(record.candidateTopicIds()),
                record.selectedTopicId(),
                record.decision(),
                record.confidence(),
                record.modelCallId(),
                clock.instant());
        return routeLogRepository.save(routeLog).getId();
    }

    /**
     * 按 Token 预算从最新消息向前选择连续上下文。
     *
     * @param summary 当前主题摘要
     * @param recentDescending 最近消息倒序列表
     * @return 按消息序号升序排列的上下文消息
     */
    private List<ContextMessage> selectWithinTokenBudget(
            MemorySummaryEntity summary,
            List<MessageEntity> recentDescending) {
        int consumedTokens = summary == null ? 0 : estimateTokens(summary.getSummaryContent());
        List<ContextMessage> selectedDescending = new ArrayList<>();

        // 从最新消息向前累加，遇到预算边界即停止，确保最终消息范围连续。
        for (MessageEntity message : recentDescending) {
            int messageTokens = normalizedTokenCount(message);
            if (consumedTokens + messageTokens > properties.contextTokenBudget()) {
                break;
            }
            selectedDescending.add(new ContextMessage(
                    message.getId(),
                    message.getSequenceNo(),
                    message.getRole(),
                    message.getMessageType(),
                    message.getContent(),
                    messageTokens));
            consumedTokens += messageTokens;
        }
        Collections.reverse(selectedDescending);
        return List.copyOf(selectedDescending);
    }

    /**
     * 保存不含重复正文的上下文快照。
     *
     * @param requestId 请求标识
     * @param context 已装配主题上下文
     */
    private void saveSnapshot(String requestId, TopicContext context) {
        // 同一请求重复装配上下文时保留首次快照，确保实际模型输入能够稳定回放。
        if (snapshotRepository.findByRequestId(requestId).isPresent()) {
            return;
        }
        Long fromSequence = context.messages().isEmpty()
                ? null : context.messages().get(0).sequenceNo();
        Long throughSequence = context.messages().isEmpty()
                ? null : context.messages().get(context.messages().size() - 1).sequenceNo();
        List<String> messageIds = context.messages().stream().map(ContextMessage::messageId).toList();

        // 哈希覆盖摘要和实际消息内容，可以识别上下文变化而不会泄露正文。
        ContextSnapshotEntity snapshot = ContextSnapshotEntity.create(
                requestId,
                context.conversationId(),
                context.topicId(),
                context.summaryId(),
                fromSequence,
                throughSequence,
                writeJson(messageIds),
                context.estimatedTokenCount(),
                hashContext(context),
                clock.instant());
        snapshotRepository.save(snapshot);
    }

    /**
     * 校验会话存在且属于当前用户。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @return 会话实体
     */
    private ConversationEntity requireOwnedConversation(String userId, String conversationId) {
        ConversationEntity conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        return conversation;
    }

    /**
     * 将主题实体转换为模型路由所需的短摘要卡片。
     *
     * @param topic 主题实体
     * @return 主题摘要卡片
     */
    private TopicSummaryCard toTopicCard(TopicEntity topic) {
        return new TopicSummaryCard(
                topic.getId(),
                topic.getTitle(),
                topic.getShortSummary(),
                topic.getStructuredState(),
                topic.getSummaryVersion(),
                topic.getLastActiveAt());
    }

    /**
     * 返回消息持久化 Token 数，缺失时使用字符长度做保守估算。
     *
     * @param message 消息实体
     * @return 非负 Token 估算
     */
    private int normalizedTokenCount(MessageEntity message) {
        return message.getTokenCount() > 0
                ? message.getTokenCount() : estimateTokens(message.getContent());
    }

    /**
     * 估算字符串 Token 数，用于尚未接入平台 Tokenizer 的持久化兜底。
     *
     * @param content 文本内容
     * @return 至少为一的近似 Token 数
     */
    private int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        // 中英文混合场景按每两个字符一个 Token 做保守估算，后续可替换为模型 Tokenizer。
        return Math.max(1, (content.length() + 1) / 2);
    }

    /**
     * 计算摘要与所选消息的总 Token 估算。
     *
     * @param summary 当前摘要
     * @param messages 所选消息
     * @return 总 Token 估算
     */
    private int estimateContextTokens(MemorySummaryEntity summary, List<ContextMessage> messages) {
        int summaryTokens = summary == null ? 0 : estimateTokens(summary.getSummaryContent());
        return summaryTokens + messages.stream().mapToInt(ContextMessage::tokenCount).sum();
    }

    /**
     * 计算上下文内容的 SHA-256 哈希。
     *
     * @param context 上下文对象
     * @return 64 位十六进制哈希
     */
    private String hashContext(TopicContext context) {
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
     * 将审计标识集合转换为 JSON。
     *
     * @param value 待序列化对象
     * @return JSON 字符串
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("上下文审计 JSON 序列化失败", ex);
        }
    }

    /**
     * 主题路由模型输入。
     *
     * @param conversationId 会话标识
     * @param activeTopicId 当前活动主题标识
     * @param currentMessageId 当前消息标识
     * @param currentQuestion 当前用户问题
     * @param recentUserQuestions 最近用户问题，不含助手回答
     * @param topicCards 最近主题摘要卡片
     */
    public record TopicRouteInput(
            String conversationId,
            String activeTopicId,
            String currentMessageId,
            String currentQuestion,
            List<UserQuestion> recentUserQuestions,
            List<TopicSummaryCard> topicCards) {
    }

    /**
     * 主题短摘要卡片。
     *
     * @param topicId 主题标识
     * @param title 主题标题
     * @param shortSummary 短摘要
     * @param structuredState 结构化业务状态 JSON
     * @param summaryVersion 摘要版本
     * @param lastActiveAt 最近活跃时间
     */
    public record TopicSummaryCard(
            String topicId,
            String title,
            String shortSummary,
            String structuredState,
            int summaryVersion,
            Instant lastActiveAt) {
    }

    /**
     * 最近用户问题。
     *
     * @param messageId 消息标识
     * @param content 用户问题正文
     * @param sequenceNo 会话消息序号
     */
    public record UserQuestion(String messageId, String content, long sequenceNo) {
    }

    /**
     * 选定主题后的模型上下文。
     *
     * @param conversationId 会话标识
     * @param topicId 主题标识
     * @param structuredState 结构化业务状态 JSON
     * @param summaryId 摘要标识
     * @param summaryContent 完整主题摘要
     * @param messages 摘要边界后的最近完整消息
     * @param estimatedTokenCount 总 Token 估算
     */
    public record TopicContext(
            String conversationId,
            String topicId,
            String structuredState,
            String summaryId,
            String summaryContent,
            List<ContextMessage> messages,
            int estimatedTokenCount) {
    }

    /**
     * 上下文中的一条完整消息。
     *
     * @param messageId 消息标识
     * @param sequenceNo 会话消息序号
     * @param role 消息角色
     * @param messageType 消息类型
     * @param content 原始消息正文
     * @param tokenCount Token 估算
     */
    public record ContextMessage(
            String messageId,
            long sequenceNo,
            MessageRole role,
            org.opengoofy.index12306.ai.agentservice.memory.domain.MessageType messageType,
            String content,
            int tokenCount) {
    }

    /**
     * 主题路由审计命令。
     *
     * @param userId 用户标识
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param currentMessageId 当前消息标识
     * @param candidateTopicIds 候选主题标识
     * @param selectedTopicId 选中主题标识
     * @param decision 路由决策
     * @param confidence 置信度
     * @param modelCallId 模型调用标识
     */
    public record RouteDecisionRecord(
            String userId,
            String requestId,
            String conversationId,
            String currentMessageId,
            List<String> candidateTopicIds,
            String selectedTopicId,
            RouteDecision decision,
            BigDecimal confidence,
            String modelCallId) {
    }
}
