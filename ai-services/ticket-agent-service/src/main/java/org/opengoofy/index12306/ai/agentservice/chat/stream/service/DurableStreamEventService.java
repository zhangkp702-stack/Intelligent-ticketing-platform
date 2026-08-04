package org.opengoofy.index12306.ai.agentservice.chat.stream.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.EventType;
import org.opengoofy.index12306.ai.agentservice.chat.stream.dao.entity.StreamEventEntity;
import org.opengoofy.index12306.ai.agentservice.chat.stream.dao.repository.StreamEventRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.TurnRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 在数据库 Turn 行锁下分配 SSE 序号，并为断线客户端提供跨实例事件重放。
 */
@Service
public class DurableStreamEventService {

    private final StreamEventRepository streamEventRepository;
    private final TurnRepository turnRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 创建持久化 SSE 事件服务。
     *
     * @param streamEventRepository 流事件仓储
     * @param turnRepository 轮次仓储
     * @param conversationRepository 会话仓储
     * @param messageRepository 最终回答仓储
     * @param objectMapper 事件 JSON 编解码器
     * @param clock 统一时间源
     */
    public DurableStreamEventService(
            StreamEventRepository streamEventRepository,
            TurnRepository turnRepository,
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.streamEventRepository = streamEventRepository;
        this.turnRepository = turnRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在事件发送给客户端之前分配序号并写入数据库。
     *
     * @param userId 当前用户标识
     * @param event 待发布事件
     * @return 新持久化事件；终态后的重复事件为空，DRAFT 校验错误不落库
     */
    @Transactional
    public Optional<ChatEvent> append(String userId, ChatEvent event) {
        requireEvent(userId, event);

        // 加锁顺序与会话删除一致：先会话、后 Turn，避免删除和事件追加形成死锁。
        TurnEntity turn = lockOwnedTurn(userId, event.turnId());
        if (turn.getStatus() == TurnStatus.DRAFT) {
            // 首次提交凭证校验失败不能污染该 Turn，用户仍可携带正确令牌重新提交。
            return event.type() == EventType.ERROR ? Optional.of(event) : Optional.empty();
        }
        if (streamEventRepository.findTerminal(turn.getId()).isPresent()) {
            // DONE 或 ERROR 之后不再分配序号，抵御重复订阅产生的尾部事件。
            return Optional.empty();
        }

        // Turn 行锁串行化同一轮次的序号分配，不依赖单机锁或 Redis 锁续期。
        long nextSequence = nextEventSequence(turn);
        ChatEvent sequenced = event.withEventSequence(nextSequence);
        insert(turn.getId(), sequenced);
        return Optional.of(sequenced);
    }

    /**
     * 查询游标后的事件；若数据库业务终态已经提交但终态事件缺失，则原子补写终态。
     *
     * @param userId 当前用户标识
     * @param turnId 服务端轮次标识
     * @param afterSequence 客户端最后收到的事件序号
     * @param limit 单次读取上限
     * @return 按事件序号升序排列的可重放事件
     */
    @Transactional
    public List<ChatEvent> poll(
            String userId,
            String turnId,
            long afterSequence,
            int limit) {
        if (afterSequence < 0L || limit <= 0) {
            throw new IllegalArgumentException("事件游标或读取数量无效");
        }

        // 每次轮询都重新校验会话所有权，不能仅凭可猜测的 turnId 读取事件正文。
        requireOwnedTurn(userId, turnId);
        List<StreamEventEntity> events = streamEventRepository.findAfterSequence(
                turnId, afterSequence, limit);
        if (!events.isEmpty()) {
            return events.stream().map(this::deserialize).toList();
        }

        // 游标超过终态序号时仍重发终态，避免非法或损坏游标让连接永久轮询。
        Optional<StreamEventEntity> existingTerminal = streamEventRepository.findTerminal(turnId);
        if (existingTerminal.isPresent()) {
            return List.of(deserialize(existingTerminal.orElseThrow()));
        }

        // 回答与 Turn 终态先于 SSE 尾事件提交时，用权威数据库结果补齐传输日志。
        return ensureTerminal(userId, turnId)
                .map(event -> List.of(event))
                .orElseGet(List::of);
    }

    /**
     * 在业务终态已提交但 SSE 终态缺失时补写唯一的 DONE 或 ERROR。
     *
     * @param userId 当前用户标识
     * @param turnId 服务端轮次标识
     * @return 本次新补写的终态；已有终态或轮次仍在运行时为空
     */
    @Transactional
    public Optional<ChatEvent> ensureTerminal(String userId, String turnId) {
        // Turn 行锁同时保护“检查终态事件”和“分配下一序号”两个动作。
        TurnEntity lockedTurn = lockOwnedTurn(userId, turnId);
        if (streamEventRepository.findTerminal(turnId).isPresent()) {
            return Optional.empty();
        }
        return createTerminalFallback(lockedTurn)
                .map(event -> {
                    long nextSequence = nextEventSequence(lockedTurn);
                    ChatEvent sequenced = event.withEventSequence(nextSequence);
                    insert(turnId, sequenced);
                    return sequenced;
                });
    }

    /**
     * 有界删除超过保留期且所属 Turn 已进入业务终态的 SSE 事件。
     *
     * @param cutoff 只清理早于该时间的事件
     * @param batchSize 单批最大删除数量
     * @return 本批实际删除数量
     */
    @Transactional
    public int cleanupTerminalEventsBefore(Instant cutoff, int batchSize) {
        if (cutoff == null || batchSize <= 0) {
            throw new IllegalArgumentException("清理时间或批次大小无效");
        }

        // 先按时间和主键稳定选出有限 ID，避免一次事务扫描或删除整个事件表。
        List<String> expiredIds = streamEventRepository.findExpiredTerminalEventIds(
                cutoff, batchSize);
        if (expiredIds.isEmpty()) {
            return 0;
        }
        // 只删除事件明细；Turn 上的序号水位和最终业务结果必须永久保留。
        return streamEventRepository.deleteByIds(expiredIds);
    }

    /**
     * 根据权威 Turn 和消息状态构造缺失的传输终态。
     *
     * @param turn 已锁定的轮次
     * @return 已完成、失败或取消事件；非终态轮次为空
     */
    private Optional<ChatEvent> createTerminalFallback(TurnEntity turn) {
        if (turn.getStatus() == TurnStatus.COMPLETED) {
            // 最终正文只从助手消息表读取，不能使用客户端重传的 message 代替。
            MessageEntity assistantMessage = Optional.ofNullable(turn.getAssistantMessageId())
                    .map(messageRepository::selectById)
                    .orElse(null);
            if (assistantMessage != null && StringUtils.hasText(assistantMessage.getContent())) {
                return Optional.of(ChatEvent.recoveredDone(
                        turn.getId(), turn.getConversationId(), assistantMessage.getContent()));
            }
            return Optional.empty();
        }
        if (turn.getStatus() == TurnStatus.FAILED) {
            // 数据库只保存稳定失败分类，恢复响应不泄露原始异常正文。
            String category = StringUtils.hasText(turn.getFailureCategory())
                    ? turn.getFailureCategory() : "TURN_FAILED";
            return Optional.of(ChatEvent.recoveredError(
                    turn.getId(), turn.getConversationId(), category, "对话处理失败，请稍后重试"));
        }
        if (turn.getStatus() == TurnStatus.CANCELLED) {
            return Optional.of(ChatEvent.recoveredError(
                    turn.getId(), turn.getConversationId(), "TURN_CANCELLED", "本次生成已停止"));
        }
        return Optional.empty();
    }

    /**
     * 在已锁定 Turn 上分配并持久化新的事件序号水位。
     *
     * @param turn 已持有数据库行锁的轮次
     * @return 清理历史事件后仍不会回退的新序号
     */
    private long nextEventSequence(TurnEntity turn) {
        // 水位更新和事件插入处于同一事务，任何一步失败都会共同回滚。
        long nextSequence = turn.nextEventSequence(clock.instant());
        if (turnRepository.updateById(turn) != 1) {
            throw new IllegalStateException("流事件序号水位更新失败");
        }
        return nextSequence;
    }

    /**
     * 按统一锁顺序读取当前用户拥有的轮次。
     *
     * @param userId 当前用户标识
     * @param turnId 服务端轮次标识
     * @return 已加数据库写锁的轮次
     */
    private TurnEntity lockOwnedTurn(String userId, String turnId) {
        TurnEntity observed = Optional.ofNullable(turnRepository.selectById(turnId))
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));
        ConversationEntity conversation = conversationRepository.findLockedById(observed.getConversationId())
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该轮次");
        }
        // 会话锁已经阻止并发删除，再锁定 Turn 保护事件序号和终态检查。
        return turnRepository.findLockedById(turnId)
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));
    }

    /**
     * 以只读方式校验轮次和当前用户的归属关系。
     *
     * @param userId 当前用户标识
     * @param turnId 服务端轮次标识
     * @return 已校验的轮次
     */
    private TurnEntity requireOwnedTurn(String userId, String turnId) {
        TurnEntity turn = Optional.ofNullable(turnRepository.selectById(turnId))
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));
        ConversationEntity conversation = Optional.ofNullable(
                        conversationRepository.selectById(turn.getConversationId()))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该轮次");
        }
        return turn;
    }

    /**
     * 序列化并插入已经分配序号的事件。
     *
     * @param turnId 服务端轮次标识
     * @param event 已绑定事件序号的事件
     */
    private void insert(String turnId, ChatEvent event) {
        try {
            // JSON 保存完整协议载荷，重放时无需依赖原执行实例的内存对象。
            String payloadJson = objectMapper.writeValueAsString(event);
            streamEventRepository.insert(StreamEventEntity.create(
                    turnId,
                    event.eventSequence(),
                    event.type(),
                    payloadJson,
                    isTerminal(event.type()),
                    clock.instant()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("流事件序列化失败", exception);
        }
    }

    /**
     * 将数据库事件载荷恢复为 SSE 协议对象。
     *
     * @param entity 持久化事件
     * @return 可直接发送的对话事件
     */
    private ChatEvent deserialize(StreamEventEntity entity) {
        try {
            return objectMapper.readValue(entity.getPayloadJson(), ChatEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("流事件反序列化失败", exception);
        }
    }

    /**
     * 校验事件持久化所需的用户、轮次和事件正文。
     *
     * @param userId 当前用户标识
     * @param event 待发布事件
     */
    private void requireEvent(String userId, ChatEvent event) {
        if (!StringUtils.hasText(userId)
                || event == null
                || !StringUtils.hasText(event.turnId())) {
            throw new IllegalArgumentException("流事件缺少用户或轮次标识");
        }
    }

    /**
     * 判断事件是否结束当前 SSE 流。
     *
     * @param eventType 事件类型
     * @return DONE 或 ERROR 时返回 true
     */
    private boolean isTerminal(EventType eventType) {
        return eventType == EventType.DONE || eventType == EventType.ERROR;
    }
}
