package org.opengoofy.index12306.ai.agentservice.memory.service;

import org.opengoofy.index12306.ai.agentservice.memory.config.AgentMemoryProperties;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationSummaryEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageType;
import org.opengoofy.index12306.ai.agentservice.memory.domain.SummaryTaskEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.SummaryTaskStatus;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ConversationSummaryRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.SummaryTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 管理会话级摘要任务的合并、MQ 状态、领取和唯一摘要提交。
 */
@Service
public class SummaryTaskService {

    private final AgentMemoryProperties properties;
    private final MessageRepository messageRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final SummaryTaskRepository taskRepository;
    private final Clock clock;

    /**
     * 创建会话摘要任务服务。
     *
     * @param properties 摘要阈值和重试配置
     * @param messageRepository 原始消息仓储
     * @param summaryRepository 唯一摘要仓储
     * @param taskRepository 摘要任务仓储
     * @param clock 统一时钟
     */
    public SummaryTaskService(
            AgentMemoryProperties properties,
            MessageRepository messageRepository,
            ConversationSummaryRepository summaryRepository,
            SummaryTaskRepository taskRepository,
            Clock clock) {
        this.properties = properties;
        this.messageRepository = messageRepository;
        this.summaryRepository = summaryRepository;
        this.taskRepository = taskRepository;
        this.clock = clock;
    }

    /**
     * 在回答事务内检查未摘要消息数量，并合并该会话的摘要目标边界。
     *
     * @param conversationId 会话标识
     * @param throughSequence 当前助手消息序号
     * @return 达到阈值时返回会话唯一任务，否则为空
     */
    @Transactional
    public Optional<SummaryTaskEntity> requestIfNeeded(String conversationId, long throughSequence) {
        ConversationSummaryEntity summary = summaryRepository.findByConversationId(conversationId).orElse(null);
        long summarizedThrough = summary == null ? 0 : summary.getSummarizedThroughSequence();
        long unsummarizedCount = messageRepository.countByConversationIdAndSequenceNoGreaterThan(
                conversationId, summarizedThrough);
        if (unsummarizedCount < properties.summaryTriggerMessageCount()) {
            return Optional.empty();
        }

        // 每个会话只维护一个任务行；连续回答只推进目标边界，不重复堆积消息。
        SummaryTaskEntity task = taskRepository.findLockedByConversationId(conversationId).orElse(null);
        int summaryVersion = summary == null ? 0 : summary.getSummaryVersion();
        if (task == null) {
            task = SummaryTaskEntity.pending(
                    conversationId,
                    throughSequence,
                    summaryVersion,
                    properties.summaryMaxAttempts(),
                    clock.instant());
            return Optional.of(taskRepository.save(task));
        }
        task.request(throughSequence, summaryVersion, clock.instant());
        return Optional.of(task);
    }

    /**
     * 查询当前有限批次的待发布任务。
     *
     * @return 待发布任务快照
     */
    @Transactional(readOnly = true)
    public List<PendingTask> pendingTasks() {
        // 发布器只读取标识和事件版本，聊天正文始终留在数据库内。
        return taskRepository.findTop100ByStatusOrderByUpdatedAtAsc(SummaryTaskStatus.PENDING)
                .stream()
                .map(task -> new PendingTask(
                        task.getId(), task.getConversationId(), task.getEventVersion(),
                        task.getDesiredThroughSequence(), task.getExpectedSummaryVersion()))
                .toList();
    }

    /**
     * 恢复租约过期或已到重试时间的任务，使消费者异常退出后仍可由 Outbox 重新发布。
     *
     * @return 本次发生状态变化的任务数量
     */
    @Transactional
    public int recoverExpiredTasks() {
        Instant now = clock.instant();
        // 分别锁定运行超时和重试到期任务，避免多实例发布器重复恢复同一行。
        List<SummaryTaskEntity> candidates = new java.util.ArrayList<>();
        candidates.addAll(taskRepository
                .findTop100ByStatusAndLeaseUntilLessThanEqualOrderByLeaseUntilAsc(
                        SummaryTaskStatus.RUNNING, now));
        candidates.addAll(taskRepository
                .findTop100ByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                        SummaryTaskStatus.RETRY_WAIT, now));
        int recovered = 0;
        for (SummaryTaskEntity task : candidates) {
            // 状态机保留事件版本与尝试次数，并在达到上限时直接终止。
            if (task.recoverForRepublish(now)) {
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * 在 RocketMQ 确认接收后记录消息标识。
     *
     * @param taskId 任务标识
     * @param eventVersion 已发布事件版本
     * @param messageId RocketMQ 消息标识
     */
    @Transactional
    public void markPublished(String taskId, long eventVersion, String messageId) {
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));
        if (task.getEventVersion() == eventVersion) {
            // 只有仍对应当前事件版本的任务才能进入已发布状态。
            task.published(messageId, clock.instant());
        }
    }

    /**
     * 幂等领取 MQ 事件并恢复模型生成所需的不可变工作输入。
     *
     * @param taskId 任务标识
     * @param eventVersion MQ 事件版本
     * @param workerId 消费节点标识
     * @return 成功领取时返回摘要工作项，重复或过期事件返回空
     */
    @Transactional
    public Optional<SummaryWorkItem> claim(String taskId, long eventVersion, String workerId) {
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));
        Instant now = clock.instant();
        if (!task.claim(eventVersion, workerId, now, properties.summaryLeaseDuration())) {
            return Optional.empty();
        }

        // 领取事务只冻结边界并读取输入，耗时模型调用不会持有数据库锁。
        ConversationSummaryEntity summary = summaryRepository.findByConversationId(task.getConversationId())
                .orElse(null);
        long summarizedThrough = summary == null ? 0 : summary.getSummarizedThroughSequence();
        long processingThrough = task.getProcessingThroughSequence();
        List<SummarySourceMessage> messages = messageRepository
                .findByConversationIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        task.getConversationId(), summarizedThrough + 1, processingThrough)
                .stream()
                .map(this::toSourceMessage)
                .toList();
        if (messages.isEmpty()) {
            throw new IllegalStateException("摘要任务没有可处理的消息");
        }

        return Optional.of(new SummaryWorkItem(
                task.getId(),
                task.getConversationId(),
                eventVersion,
                task.getExpectedSummaryVersion(),
                processingThrough,
                summary == null ? null : summary.getSummaryContent(),
                summary == null ? null : summary.getStructuredState(),
                messages));
    }

    /**
     * 原子更新会话唯一摘要并完成当前处理边界。
     *
     * @param taskId 任务标识
     * @param result 模型生成结果
     * @return 更新后的会话摘要
     */
    @Transactional
    public ConversationSummaryEntity complete(String taskId, SummaryGenerationResult result) {
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));
        ConversationSummaryEntity summary = summaryRepository
                .findLockedByConversationId(task.getConversationId())
                .orElseGet(() -> summaryRepository.save(
                        ConversationSummaryEntity.empty(task.getConversationId(), clock.instant())));
        if (summary.getSummaryVersion() != task.getExpectedSummaryVersion()) {
            throw new IllegalStateException("摘要任务版本已经过期");
        }

        long processingThrough = task.getProcessingThroughSequence();
        long sourceCount = messageRepository.countByConversationIdAndSequenceNoBetween(
                task.getConversationId(), summary.getSummarizedThroughSequence() + 1, processingThrough);
        int boundedSourceCount = (int) Math.min(Integer.MAX_VALUE, sourceCount);
        // 摘要行、摘要边界和任务状态在同一事务中提交，失败时保持旧摘要可用。
        summary.replace(
                task.getExpectedSummaryVersion(),
                processingThrough,
                result.summaryContent(),
                result.structuredState(),
                boundedSourceCount,
                result.providerId(),
                result.candidateId(),
                result.modelId(),
                clock.instant());
        task.succeed(summary.getSummaryVersion(), clock.instant());
        return summary;
    }

    /**
     * 记录摘要消费失败并决定是否继续由 RocketMQ 重投。
     *
     * @param taskId 任务标识
     * @param category 失败分类
     * @param safeMessage 脱敏失败说明
     * @return 仍可重试时返回 true
     */
    @Transactional
    public boolean fail(String taskId, String category, String safeMessage) {
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));
        return task.fail(category, safeMessage, clock.instant(), properties.summaryRetryDelay());
    }

    /**
     * 将原始消息转换为不会继续访问持久化上下文的工作项值对象。
     *
     * @param message 原始消息实体
     * @return 摘要来源消息
     */
    private SummarySourceMessage toSourceMessage(MessageEntity message) {
        return new SummarySourceMessage(
                message.getId(), message.getSequenceNo(), message.getRole(),
                message.getMessageType(), message.getContent(), message.getTokenCount());
    }

    /**
     * 待发布摘要任务的最小消息数据。
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @param eventVersion 事件版本
     * @param throughSequence 目标消息边界
     * @param expectedSummaryVersion 预期摘要版本
     */
    public record PendingTask(
            String taskId,
            String conversationId,
            long eventVersion,
            long throughSequence,
            int expectedSummaryVersion) {
    }

    /**
     * 摘要模型使用的不可变工作输入。
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @param eventVersion 事件版本
     * @param expectedSummaryVersion 预期摘要版本
     * @param throughSequence 本次冻结的消息边界
     * @param previousSummary 上一份完整摘要
     * @param previousStructuredState 上一份结构化状态
     * @param messages 本次新增原始消息
     */
    public record SummaryWorkItem(
            String taskId,
            String conversationId,
            long eventVersion,
            int expectedSummaryVersion,
            long throughSequence,
            String previousSummary,
            String previousStructuredState,
            List<SummarySourceMessage> messages) {
    }

    /**
     * 摘要来源消息。
     *
     * @param messageId 消息标识
     * @param sequenceNo 消息序号
     * @param role 消息角色
     * @param messageType 消息类型
     * @param content 原始正文
     * @param tokenCount Token 估算
     */
    public record SummarySourceMessage(
            String messageId,
            long sequenceNo,
            MessageRole role,
            MessageType messageType,
            String content,
            int tokenCount) {
    }

    /**
     * 摘要模型生成结果。
     *
     * @param summaryContent 新的完整累计摘要
     * @param structuredState 新的结构化状态
     * @param providerId 实际模型平台
     * @param candidateId 实际候选模型
     * @param modelId 实际模型标识
     */
    public record SummaryGenerationResult(
            String summaryContent,
            String structuredState,
            String providerId,
            String candidateId,
            String modelId) {
    }
}
