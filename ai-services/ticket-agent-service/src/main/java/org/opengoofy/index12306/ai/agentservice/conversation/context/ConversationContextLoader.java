package org.opengoofy.index12306.ai.agentservice.conversation.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.opengoofy.index12306.ai.agentservice.conversation.config.AgentMemoryProperties;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationHistoryService;
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
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ContextSnapshotRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationSummaryRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.TurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
import java.util.Optional;

/**
 * 按会话唯一摘要和摘要边界后的完整轮次装配回答上下文。
 * <p>
 * 摘要正常覆盖历史时只加载最近窗口；摘要积压或失败导致未覆盖轮次超过窗口时，
 * 在回退上限内加载全部未覆盖终态轮次，超过上限则返回可重试提示。
 */
@Service
public class ConversationContextLoader {

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
    public ConversationContextLoader(
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
     * 加载会话摘要和完整历史窗口，并保存包含当前问题的输入快照。
     * <p>
     * 摘要积压时最多加载配置上限内的全部未覆盖轮次，避免一次性把过大的历史上下文传给模型。
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
        ConversationEntity conversation = Optional.ofNullable(conversationRepository.selectById(conversationId))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }

        // 摘要只提供压缩历史，当前正在执行的用户问题始终独立于历史轮次。
        ConversationSummaryEntity summary = summaryRepository.findByConversationId(conversationId).orElse(null);
        // 如果是null，说明第一次，不为null就获取最后覆盖的消息的序列id
        long summarizedThrough = summary == null ? 0 : summary.getSummarizedThroughSequence();
        // 最近窗口同时保留完成、失败和取消轮次，避免只有用户问题的失败轮次被静默遗漏。
        // 一次查询到“回退上限 + 1”条即可同时判断是否需要全量回退或拒绝，避免无上限读取数据库。
        List<TurnEntity> recentTurns = turnRepository.findRecentTerminalTurns(
                conversationId,
                summarizedThrough,
                currentTurnId,
                properties.maxUncoveredTurnFallback() + 1);
        if (recentTurns.size() > properties.recentTurnLimit()) {
            if (recentTurns.size() > properties.maxUncoveredTurnFallback()) {
                // 超过安全回退上限时不加载更多原文，等待摘要任务推进后由用户重试。
                throw new ConversationHistoryUnavailableException("会话历史较多，系统正在整理，请稍后重试");
            }
            // 摘要未及时推进但仍在安全范围内时，探测查询已经包含全部未覆盖轮次。
            // 不再截断为最近窗口，优先保证模型不遗漏用户已经提供的信息。
        }

        // 一次性加载不同轮次不同角色的消息，避免按轮次逐条查询形成 N+1，key是消息id，value是消息体
        Map<String, MessageEntity> messagesById = loadMessagesById(recentTurns);

        // 正常只进入最近窗口；摘要落后时这里会接收全部未覆盖轮次。
        LoadedHistory loadedHistory = loadRecentHistory(recentTurns, messagesById);
        ConversationHistoryContext context = new ConversationHistoryContext(
                conversationId,
                summary == null ? null : summary.getId(),
                summary == null ? null : summary.getSummaryContent(),
                summary == null ? null : summary.getStructuredState(),
                summary == null ? null : summary.getSummaryVersion(),
                summarizedThrough,
                loadedHistory.turns(),
                AgentChatMessage.user(currentQuestion),
                loadedHistory.messageIds(),
                loadedHistory.fromSequence(),
                loadedHistory.throughSequence());

        // 快照记录本次历史与当前问题的真实输入边界，但不复制消息正文。
        // todo
        saveSnapshot(requestId, context, currentUserMessageId, currentUserSequence);
        return context;
    }

    /**
     * 表示未覆盖历史超过安全回退上限，当前请求应等待摘要推进后重试。
     */
    public static final class ConversationHistoryUnavailableException extends IllegalStateException {

        /**
         * 创建历史回退上限异常。
         *
         * @param message 用户可读的重试提示
         */
        public ConversationHistoryUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * 批量加载完整轮次引用的用户和助手消息。
     *
     * @param turns 最近完成的轮次
     * @return 以消息标识索引的消息集合
     */
    private Map<String, MessageEntity> loadMessagesById(List<TurnEntity> turns) {
        if (turns.isEmpty()) {
            // 新会话没有历史轮次时直接返回，避免向 MyBatis-Plus 传入空 IN 集合。
            return Map.of();
        }

        List<String> messageIds = new ArrayList<>();
        for (TurnEntity turn : turns) {
            // 每条终态轮次都有用户消息；失败或取消轮次可能没有助手消息。
            messageIds.add(turn.getUserMessageId());
            if (turn.getAssistantMessageId() != null) {
                messageIds.add(turn.getAssistantMessageId());
            }
        }
        Map<String, MessageEntity> messagesById = new LinkedHashMap<>();
        messageRepository.selectByIds(messageIds)
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
        // 取出用户消息
        MessageEntity userMessage = requireMessage(
                turn.getUserMessageId(), MessageRole.USER, messagesById);
        MessageEntity assistantMessage = turn.getAssistantMessageId() == null
                ? null : requireMessage(turn.getAssistantMessageId(), MessageRole.ASSISTANT, messagesById);

        // 用户问题必须是文本；存在助手消息时也只允许文本，工具审计消息不进入模型历史。
        if (userMessage.getMessageType() != MessageType.TEXT
                || assistantMessage != null && assistantMessage.getMessageType() != MessageType.TEXT) {
            throw new IllegalStateException("历史轮次只能包含文本用户消息和文本助手消息");
        }
        // 解析成功且确有改写时优先使用已持久化的独立问题；原始消息仍作为审计真相保留。
        ConversationTurnContext context = new ConversationTurnContext(
                turn.getId(),
                AgentChatMessage.user(resolveHistoricalQuestion(turn, userMessage.getContent())),
                assistantMessage == null ? null : AgentChatMessage.assistant(assistantMessage.getContent()));
        return new LoadedTurn(context, userMessage, assistantMessage);
    }

    /**
     * 从已校验的问题解析结果中恢复历史轮次的独立问题，异常时安全回退原文。
     *
     * @param turn 历史轮次
     * @param originalQuestion 原始用户问题
     * @return 可供后续模型理解的历史问题文本
     */
    private String resolveHistoricalQuestion(TurnEntity turn, String originalQuestion) {
        if (!turn.isHasRewrite()
                || !"SUCCEEDED".equals(turn.getRewriteStatus())
                || !StringUtils.hasText(turn.getQuestionResolutionJson())) {
            return originalQuestion;
        }
        try {
            // 只读取经过服务端问题解析校验的独立问题字段，解析异常绝不能导致历史原文丢失。
            JsonNode tasks = objectMapper.readTree(turn.getQuestionResolutionJson()).path("tasks");
            List<String> questions = new ArrayList<>();
            for (JsonNode task : tasks) {
                String standaloneQuestion = task.path("standaloneQuestion").asText();
                if (StringUtils.hasText(standaloneQuestion)) {
                    questions.add(standaloneQuestion.trim());
                }
            }
            return questions.isEmpty() ? originalQuestion : String.join("；", questions);
        } catch (JsonProcessingException exception) {
            // 历史 JSON 兼容异常仅影响重写副本，不影响不可变的用户原始消息。
            return originalQuestion;
        }
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
     * 将查询到的最近完整轮次全部装配为模型历史。
     *
     * @param recentDescending 摘要边界后的完整轮次倒序列表
     * @param messagesById 已批量加载的消息索引
     * @return 按消息序号升序排列的完整历史轮次
     */
    private LoadedHistory loadRecentHistory(
            List<TurnEntity> recentDescending,
            Map<String, MessageEntity> messagesById) {
        List<LoadedTurn> loadedDescending = new ArrayList<>();

        // 轮次查询已经完成窗口或全量选择，此处只完成消息校验和对象转换。
        for (TurnEntity turn : recentDescending) {
            loadedDescending.add(toLoadedTurn(turn, messagesById));
        }
        Collections.reverse(loadedDescending);

        // 这里获取到内容
        List<ConversationTurnContext> turns = loadedDescending.stream()
                .map(LoadedTurn::context)
                .toList();
        // 加载id，一个turn是一个完整的问答，记录本次请求加载了那些历史消息
        List<String> messageIds = new ArrayList<>();
        // 获取到id
        for (LoadedTurn loadedTurn : loadedDescending) {
            // 用户消息 id 始终存在。
            messageIds.add(loadedTurn.userMessage().getId());
            if (loadedTurn.assistantMessage() != null) {
                // 完成轮次才有助手回复消息。
                messageIds.add(loadedTurn.assistantMessage().getId());
            }
        }
        // 这里是在获取范围，首先获取最高的消息序号
        Long fromSequence = loadedDescending.isEmpty()
                ? null : loadedDescending.get(0).userMessage().getSequenceNo();
        // 这里是获取结尾的序号
        Long throughSequence = loadedDescending.isEmpty() ? null : loadedDescending.stream()
                .mapToLong(loadedTurn -> loadedTurn.assistantMessage() == null
                        ? loadedTurn.userMessage().getSequenceNo()
                        : loadedTurn.assistantMessage().getSequenceNo())
                .max()
                .orElseThrow();
        // turns 是消息具体内容，然后是消息的范围，消息的起始id，消息的终止id
        return new LoadedHistory(turns, List.copyOf(messageIds), fromSequence, throughSequence);
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
                hashContext(context),
                clock.instant());
        snapshotRepository.insert(snapshot);
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
                if (turn.assistantMessage() != null) {
                    // 用户问题没有回答时只记录已实际加载的用户消息。
                    updateDigest(digest, turn.assistantMessage());
                }
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
     */
    private record LoadedTurn(
            ConversationTurnContext context,
            MessageEntity userMessage,
            MessageEntity assistantMessage) {
    }

    /**
     * 已加载并按时间正序整理的历史上下文。
     *
     * @param turns 按时间正序排列的完整轮次
     * @param messageIds 按时间正序排列的消息标识
     * @param fromSequence 历史起始序号
     * @param throughSequence 历史结束序号
     */
    private record LoadedHistory(
            List<ConversationTurnContext> turns,
            List<String> messageIds,
            Long fromSequence,
            Long throughSequence) {
    }
}
