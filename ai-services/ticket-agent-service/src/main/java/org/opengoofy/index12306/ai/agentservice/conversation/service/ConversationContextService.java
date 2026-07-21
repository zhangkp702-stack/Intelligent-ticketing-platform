package org.opengoofy.index12306.ai.agentservice.conversation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.conversation.config.AgentMemoryProperties;
import org.opengoofy.index12306.ai.agentservice.conversation.context.AgentChatMessage;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationHistoryContext;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationTurnContext;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ContextSnapshotEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationSummaryEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageType;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ContextSnapshotRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationSummaryRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.TurnRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按会话唯一摘要和摘要边界后的完整轮次装配回答上下文。
 */
@Service
public class ConversationContextService {

    private final AgentMemoryProperties properties;
    private final ConversationRepository conversationRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final MessageRepository messageRepository;
    private final TurnRepository turnRepository;
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
     * @param turnRepository 问答轮次仓储
     * @param snapshotRepository 上下文快照仓储
     * @param objectMapper JSON 序列化器
     * @param clock 统一时钟
     */
    public ConversationContextService(
            AgentMemoryProperties properties,
            ConversationRepository conversationRepository,
            ConversationSummaryRepository summaryRepository,
            MessageRepository messageRepository,
            TurnRepository turnRepository,
            ContextSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.conversationRepository = conversationRepository;
        this.summaryRepository = summaryRepository;
        this.messageRepository = messageRepository;
        this.turnRepository = turnRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 加载会话摘要和最近完整轮次，并保存包含当前问题的输入快照。
     *
     * @param userId 当前用户标识
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param currentTurnId 当前正在执行的轮次标识
     * @param currentUserMessageId 当前用户消息标识
     * @param currentUserSequence 当前用户消息序号
     * @param currentQuestion 当前用户问题
     * @return 当前问题独立于历史轮次列表的会话上下文
     */
    @Transactional
    public ConversationHistoryContext load(
            String userId,
            String requestId,
            String conversationId,
            String currentTurnId,
            String currentUserMessageId,
            long currentUserSequence,
            String currentQuestion) {
        // 先校验会话归属，防止通过会话标识读取其他用户的摘要和消息。
        ConversationEntity conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }

        // 摘要只提供压缩历史，当前正在执行的用户问题始终独立于历史轮次。
        ConversationSummaryEntity summary = summaryRepository.findByConversationId(conversationId).orElse(null);
        long summarizedThrough = summary == null ? 0 : summary.getSummarizedThroughSequence();
        List<TurnEntity> recentTurns = turnRepository.findRecentCompletedTurns(
                conversationId,
                TurnStatus.COMPLETED,
                summarizedThrough,
                currentTurnId,
                PageRequest.of(0, properties.recentTurnLimit()));

        // 一次性加载轮次两侧消息，避免按轮次逐条查询形成 N+1。
        Map<String, MessageEntity> messagesById = loadMessagesById(recentTurns);
        SelectedHistory selected = selectWithinTokenBudget(
                summary, recentTurns, messagesById, currentQuestion);
        ConversationHistoryContext context = new ConversationHistoryContext(
                conversationId,
                summary == null ? null : summary.getId(),
                summary == null ? null : summary.getSummaryContent(),
                summary == null ? null : summary.getStructuredState(),
                summary == null ? null : summary.getSummaryVersion(),
                summarizedThrough,
                selected.turns(),
                AgentChatMessage.user(currentQuestion),
                selected.messageIds(),
                selected.fromSequence(),
                selected.throughSequence(),
                selected.estimatedTokenCount());

        // 快照记录本次历史与当前问题的真实输入边界，但不复制消息正文。
        saveSnapshot(requestId, context, currentUserMessageId, currentUserSequence);
        return context;
    }

    /**
     * 批量加载完整轮次引用的用户和助手消息。
     *
     * @param turns 最近完成的轮次
     * @return 以消息标识索引的消息集合
     */
    private Map<String, MessageEntity> loadMessagesById(List<TurnEntity> turns) {
        List<String> messageIds = new ArrayList<>();
        for (TurnEntity turn : turns) {
            // 完成轮次必须同时引用用户消息和助手消息，批量读取可避免循环访问数据库。
            messageIds.add(turn.getUserMessageId());
            messageIds.add(turn.getAssistantMessageId());
        }
        Map<String, MessageEntity> messagesById = new LinkedHashMap<>();
        messageRepository.findAllById(messageIds)
                .forEach(message -> messagesById.put(message.getId(), message));
        return messagesById;
    }

    /**
     * 将持久化轮次转换为经过角色和类型校验的历史轮次。
     *
     * @param turn 已完成轮次
     * @param messagesById 已批量加载的消息索引
     * @return 可进入模型上下文的完整轮次
     */
    private LoadedTurn toLoadedTurn(TurnEntity turn, Map<String, MessageEntity> messagesById) {
        MessageEntity userMessage = requireMessage(
                turn.getUserMessageId(), MessageRole.USER, messagesById);
        MessageEntity assistantMessage = requireMessage(
                turn.getAssistantMessageId(), MessageRole.ASSISTANT, messagesById);

        // 历史上下文只接收文本问答，工具消息仍保留在数据库审计记录中。
        if (userMessage.getMessageType() != MessageType.TEXT
                || assistantMessage.getMessageType() != MessageType.TEXT) {
            throw new IllegalStateException("完整历史轮次必须由文本用户消息和文本助手消息组成");
        }
        ConversationTurnContext context = new ConversationTurnContext(
                turn.getId(),
                AgentChatMessage.user(userMessage.getContent()),
                AgentChatMessage.assistant(assistantMessage.getContent()));
        int tokenCount = normalizedTokenCount(userMessage) + normalizedTokenCount(assistantMessage);
        return new LoadedTurn(context, userMessage, assistantMessage, tokenCount);
    }

    /**
     * 读取并校验轮次引用的消息。
     *
     * @param messageId 消息标识
     * @param expectedRole 期望角色
     * @param messagesById 已批量加载的消息索引
     * @return 与轮次引用匹配的消息
     */
    private MessageEntity requireMessage(
            String messageId,
            MessageRole expectedRole,
            Map<String, MessageEntity> messagesById) {
        MessageEntity message = messagesById.get(messageId);
        if (message == null) {
            throw new IllegalStateException("完整历史轮次引用的消息不存在");
        }
        if (message.getRole() != expectedRole) {
            throw new IllegalStateException("完整历史轮次的消息角色不匹配");
        }
        return message;
    }

    /**
     * 在 Token 预算内从最新轮次向前选择连续上下文。
     *
     * @param summary 当前会话摘要
     * @param recentDescending 摘要边界后的完整轮次倒序列表
     * @param messagesById 已批量加载的消息索引
     * @param currentQuestion 当前用户问题
     * @return 按消息序号升序排列的完整历史轮次
     */
    private SelectedHistory selectWithinTokenBudget(
            ConversationSummaryEntity summary,
            List<TurnEntity> recentDescending,
            Map<String, MessageEntity> messagesById,
            String currentQuestion) {
        int consumedTokens = estimateSummaryTokens(summary) + estimateTokens(currentQuestion);
        List<LoadedTurn> selectedDescending = new ArrayList<>();

        // 每次只加入完整的用户—助手轮次，避免 Token 截断后留下孤立消息。
        for (TurnEntity turn : recentDescending) {
            LoadedTurn loadedTurn = toLoadedTurn(turn, messagesById);
            if (consumedTokens + loadedTurn.tokenCount() > properties.contextTokenBudget()) {
                break;
            }
            selectedDescending.add(loadedTurn);
            consumedTokens += loadedTurn.tokenCount();
        }
        Collections.reverse(selectedDescending);

        // 反转后按时间顺序生成模型历史和快照引用。
        List<ConversationTurnContext> turns = selectedDescending.stream()
                .map(LoadedTurn::context)
                .toList();
        List<String> messageIds = new ArrayList<>();
        for (LoadedTurn loadedTurn : selectedDescending) {
            messageIds.add(loadedTurn.userMessage().getId());
            messageIds.add(loadedTurn.assistantMessage().getId());
        }
        Long fromSequence = selectedDescending.isEmpty()
                ? null : selectedDescending.get(0).userMessage().getSequenceNo();
        Long throughSequence = selectedDescending.isEmpty()
                ? null : selectedDescending.get(selectedDescending.size() - 1)
                        .assistantMessage().getSequenceNo();
        return new SelectedHistory(
                turns, List.copyOf(messageIds), fromSequence, throughSequence, consumedTokens);
    }

    /**
     * 保存本次模型输入使用的会话摘要版本和消息范围。
     *
     * @param requestId 请求标识
     * @param context 已装配上下文
     * @param currentUserMessageId 当前用户消息标识
     * @param currentUserSequence 当前用户消息序号
     */
    private void saveSnapshot(
            String requestId,
            ConversationHistoryContext context,
            String currentUserMessageId,
            long currentUserSequence) {
        if (snapshotRepository.findByRequestId(requestId).isPresent()) {
            return;
        }
        Long fromSequence = context.fromSequence() == null
                ? currentUserSequence : context.fromSequence();
        List<String> messageIds = new ArrayList<>(context.messageIds());
        messageIds.add(currentUserMessageId);

        // 使用上下文哈希支持问题回放，同时避免在审计表中重复存储正文。
        ContextSnapshotEntity snapshot = ContextSnapshotEntity.create(
                requestId,
                context.conversationId(),
                context.summaryId(),
                context.summaryVersion(),
                context.summarizedThroughSequence(),
                fromSequence,
                currentUserSequence,
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
     * 计算摘要正文与结构化状态的 Token 估算。
     *
     * @param summary 当前会话摘要
     * @return 摘要相关 Token 估算
     */
    private int estimateSummaryTokens(ConversationSummaryEntity summary) {
        if (summary == null) {
            return 0;
        }
        // 摘要正文和结构化状态都会进入系统消息，因此需要共同占用预算。
        return estimateTokens(summary.getSummaryContent())
                + estimateTokens(summary.getStructuredState());
    }

    /**
     * 计算摘要、完整历史轮次和当前问题的 SHA-256 哈希。
     *
     * @param context 会话上下文
     * @return 十六进制哈希
     */
    private String hashContext(ConversationHistoryContext context) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (context.summaryContent() != null) {
                digest.update(context.summaryContent().getBytes(StandardCharsets.UTF_8));
            }
            if (context.structuredState() != null) {
                digest.update(context.structuredState().getBytes(StandardCharsets.UTF_8));
            }
            for (ConversationTurnContext turn : context.recentTurns()) {
                updateDigest(digest, turn.userMessage());
                updateDigest(digest, turn.assistantMessage());
            }
            updateDigest(digest, context.currentQuestion());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少 SHA-256", ex);
        }
    }

    /**
     * 将一条标准角色消息加入上下文摘要。
     *
     * @param digest SHA-256 摘要器
     * @param message 标准角色消息
     */
    private void updateDigest(MessageDigest digest, AgentChatMessage message) {
        // 同时写入角色和正文，避免不同角色下相同文本产生相同上下文哈希。
        digest.update(message.role().name().getBytes(StandardCharsets.UTF_8));
        digest.update(message.content().getBytes(StandardCharsets.UTF_8));
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
     * 已加载并完成校验的一组持久化轮次数据。
     *
     * @param context 标准历史轮次
     * @param userMessage 用户消息实体
     * @param assistantMessage 助手消息实体
     * @param tokenCount 完整轮次 Token 估算
     */
    private record LoadedTurn(
            ConversationTurnContext context,
            MessageEntity userMessage,
            MessageEntity assistantMessage,
            int tokenCount) {
    }

    /**
     * Token 预算筛选后的历史上下文。
     *
     * @param turns 按时间正序排列的完整轮次
     * @param messageIds 按时间正序排列的消息标识
     * @param fromSequence 历史起始序号
     * @param throughSequence 历史结束序号
     * @param estimatedTokenCount 包含当前问题的总 Token 估算
     */
    private record SelectedHistory(
            List<ConversationTurnContext> turns,
            List<String> messageIds,
            Long fromSequence,
            Long throughSequence,
            int estimatedTokenCount) {
    }
}
