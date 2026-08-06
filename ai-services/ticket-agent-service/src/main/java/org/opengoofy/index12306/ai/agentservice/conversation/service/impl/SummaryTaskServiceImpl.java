package org.opengoofy.index12306.ai.agentservice.conversation.service.impl;

import org.opengoofy.index12306.ai.agentservice.conversation.config.AgentMemoryProperties;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationSummaryEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageType;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.SummaryTaskEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.SummaryTaskStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationSummaryRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.SummaryTaskRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskService;
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
public class SummaryTaskServiceImpl implements SummaryTaskService {

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
    public SummaryTaskServiceImpl(
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
    @Override
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
            taskRepository.insert(task);
            return Optional.of(task);
        }
        if (task.getStatus() != SummaryTaskStatus.SUCCEEDED
                && task.getStatus() != SummaryTaskStatus.FAILED) {
            // 已排队批次冻结原始边界：下一轮请求可同步补齐该批次，不能把当前问题塞进历史摘要。
            return Optional.of(task);
        }
        task.request(throughSequence, summaryVersion, clock.instant());
        // 摘要目标边界在 MyBatis-Plus 下需要显式更新，防止后续消息覆盖该请求。
        taskRepository.updateById(task);
        return Optional.of(task);
    }

    /**
     * 查询当前有限批次的待发布任务。
     *
     * @return 待发布任务快照
     */
    @Transactional(readOnly = true)
    @Override
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
    @Override
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
                taskRepository.updateById(task);
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
    @Override
    public void markPublished(String taskId, long eventVersion, String messageId) {
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));
        if (task.getEventVersion() == eventVersion) {
            // 只有仍对应当前事件版本的任务才能进入已发布状态。
            task.published(messageId, clock.instant());
            taskRepository.updateById(task);
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
    @Override
    public Optional<SummaryWorkItem> claim(String taskId, long eventVersion, String workerId) {
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));
        Instant now = clock.instant();
        if (!task.claim(eventVersion, workerId, now, properties.summaryLeaseDuration())) {
            return Optional.empty();
        }
        // 领取状态和租约必须先持久化，避免另一个消费者重复领取同一摘要任务。
        taskRepository.updateById(task);

        return Optional.of(toWorkItem(task));
    }

    /**
     * 为上下文加载领取完全位于当前问题之前的已排队摘要批次。
     *
     * @param conversationId 会话标识
     * @param beforeSequence 当前用户消息之前的最大消息序号
     * @param workerId 当前执行节点标识
     * @return 成功领取时返回不可变摘要输入；无可领取历史批次时为空
     */
    @Transactional
    @Override
    public Optional<SummaryWorkItem> claimForContext(
            String conversationId,
            long beforeSequence,
            String workerId) {
        SummaryTaskEntity task = taskRepository.findLockedByConversationId(conversationId).orElse(null);
        if (task == null || task.getDesiredThroughSequence() >= beforeSequence) {
            return Optional.empty();
        }
        if (!task.claim(task.getEventVersion(), workerId, clock.instant(), properties.summaryLeaseDuration())) {
            return Optional.empty();
        }
        // 先持久化领取状态，再在事务外调用模型，避免与 MQ 消费者重复处理同一批次。
        taskRepository.updateById(task);
        return Optional.of(toWorkItem(task));
    }

    /**
     * 读取已领取任务对应的旧摘要和冻结消息范围。
     *
     * @param task 已领取摘要任务
     * @return 不再依赖持久化会话状态的摘要模型输入
     */
    private SummaryWorkItem toWorkItem(SummaryTaskEntity task) {
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

        return new SummaryWorkItem(
                task.getId(),
                task.getConversationId(),
                task.getEventVersion(),
                task.getExpectedSummaryVersion(),
                processingThrough,
                summary == null ? null : summary.getSummaryContent(),
                summary == null ? null : summary.getStructuredState(),
                messages);
    }

    /**
     * 原子更新会话唯一摘要并完成当前处理边界。
     *
     * @param taskId 任务标识
     * @param result 模型生成结果
     * @return 更新后的会话摘要
     */
    @Transactional
    @Override
    public ConversationSummaryEntity complete(String taskId, SummaryGenerationResult result) {
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));
        ConversationSummaryEntity summary = summaryRepository
                .findLockedByConversationId(task.getConversationId())
                .orElseGet(() -> {
                    ConversationSummaryEntity emptySummary =
                            ConversationSummaryEntity.empty(task.getConversationId(), clock.instant());
                    summaryRepository.insert(emptySummary);
                    return emptySummary;
                });
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
        summaryRepository.updateById(summary);
        taskRepository.updateById(task);
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
    @Override
    public boolean fail(String taskId, String category, String safeMessage) {
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));
        boolean retryable = task.fail(category, safeMessage, clock.instant(), properties.summaryRetryDelay());
        // 失败分类、重试时间和终态均需显式写回，供 Outbox 恢复任务读取。
        taskRepository.updateById(task);
        return retryable;
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

}
