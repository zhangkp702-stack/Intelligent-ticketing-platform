package org.opengoofy.index12306.ai.agentservice.memory.service;

import org.opengoofy.index12306.ai.agentservice.memory.config.AgentMemoryProperties;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MemorySummaryEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MemorySummaryStatus;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageType;
import org.opengoofy.index12306.ai.agentservice.memory.domain.SummaryTaskEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TopicEntity;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.MemorySummaryRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.MessageRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.SummaryTaskRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.TopicRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 管理主题摘要任务的入队、领取、重试和版本化提交。
 */
@Service
public class SummaryTaskService {

    private final AgentMemoryProperties properties;
    private final ConversationRepository conversationRepository;
    private final TopicRepository topicRepository;
    private final MessageRepository messageRepository;
    private final MemorySummaryRepository summaryRepository;
    private final SummaryTaskRepository taskRepository;
    private final Clock clock;

    /**
     * 创建摘要任务状态服务。
     *
     * @param properties 摘要阈值和重试配置
     * @param conversationRepository 会话仓储
     * @param topicRepository 主题仓储
     * @param messageRepository 消息仓储
     * @param summaryRepository 摘要仓储
     * @param taskRepository 摘要任务仓储
     * @param clock 统一时钟
     */
    public SummaryTaskService(
            AgentMemoryProperties properties,
            ConversationRepository conversationRepository,
            TopicRepository topicRepository,
            MessageRepository messageRepository,
            MemorySummaryRepository summaryRepository,
            SummaryTaskRepository taskRepository,
            Clock clock) {
        this.properties = properties;
        this.conversationRepository = conversationRepository;
        this.topicRepository = topicRepository;
        this.messageRepository = messageRepository;
        this.summaryRepository = summaryRepository;
        this.taskRepository = taskRepository;
        this.clock = clock;
    }

    /**
     * 在未压缩消息达到阈值时使用独立事务幂等创建摘要任务。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param topicId 主题标识
     * @param throughSequence 本次允许摘要覆盖到的最大消息序号
     * @return 创建或已存在的任务，未达到阈值时为空
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SummaryTaskEntity> enqueueIfNeeded(
            String userId,
            String conversationId,
            String topicId,
            long throughSequence) {
        requireOwnedConversation(userId, conversationId);
        TopicEntity topic = topicRepository.findLockedById(topicId)
                .filter(candidate -> candidate.getConversationId().equals(conversationId))
                .orElseThrow(() -> new IllegalArgumentException("主题不存在或不属于当前会话"));

        // 只统计当前主题且位于上次摘要边界后的消息，其他主题消息不会触发本任务。
        List<MessageEntity> sourceMessages = messageRepository
                .findByTopicIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        topicId, topic.getSummarizedThroughSequence())
                .stream()
                .filter(message -> message.getSequenceNo() <= throughSequence)
                .toList();
        if (sourceMessages.size() < properties.summaryTriggerMessageCount()) {
            return Optional.empty();
        }

        long actualFrom = sourceMessages.get(0).getSequenceNo();
        long actualThrough = sourceMessages.get(sourceMessages.size() - 1).getSequenceNo();
        Optional<SummaryTaskEntity> existingTask = taskRepository
                .findByTopicIdAndThroughSequence(topicId, actualThrough);
        if (existingTask.isPresent()) {
            return existingTask;
        }

        // 任务冻结来源范围和期望版本，完成时再次校验主题版本防止旧任务覆盖新摘要。
        SummaryTaskEntity task = SummaryTaskEntity.pending(
                conversationId,
                topicId,
                actualFrom,
                actualThrough,
                topic.getSummaryVersion() + 1,
                properties.summaryMaxAttempts(),
                clock.instant());
        return Optional.of(taskRepository.save(task));
    }

    /**
     * 使用行锁领取摘要任务并加载不可变工作输入。
     *
     * @param taskId 任务标识
     * @param workerId 工作节点标识
     * @return 旧摘要和新增原始消息组成的工作项
     */
    @Transactional
    public SummaryWorkItem claim(String taskId, String workerId) {
        Instant now = clock.instant();
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));

        // 建立有限租约后加载冻结消息范围，事务提交后模型调用不再占用数据库锁。
        task.claim(workerId, now, properties.summaryLeaseDuration());
        List<SummarySourceMessage> messages = messageRepository
                .findByTopicIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        task.getTopicId(), task.getFromSequence(), task.getThroughSequence())
                .stream()
                .map(message -> new SummarySourceMessage(
                        message.getId(),
                        message.getSequenceNo(),
                        message.getRole(),
                        message.getMessageType(),
                        message.getContent(),
                        message.getTokenCount()))
                .toList();
        MemorySummaryEntity previousSummary = summaryRepository
                .findFirstByTopicIdAndStatusOrderByVersionNoDesc(
                        task.getTopicId(), MemorySummaryStatus.ACTIVE)
                .orElse(null);

        return new SummaryWorkItem(
                task.getId(),
                task.getConversationId(),
                task.getTopicId(),
                task.getExpectedSummaryVersion(),
                previousSummary == null ? null : previousSummary.getSummaryContent(),
                previousSummary == null ? null : previousSummary.getStructuredState(),
                messages);
    }

    /**
     * 原子写入新摘要、替代旧版本、推进主题边界并完成任务。
     *
     * @param taskId 任务标识
     * @param result 模型生成结果
     * @return 新活动摘要
     */
    @Transactional
    public MemorySummaryEntity complete(String taskId, SummaryGenerationResult result) {
        Instant now = clock.instant();
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));
        TopicEntity topic = topicRepository.findLockedById(task.getTopicId())
                .orElseThrow(() -> new IllegalStateException("摘要任务主题不存在"));
        if (task.getExpectedSummaryVersion() != topic.getSummaryVersion() + 1) {
            throw new IllegalStateException("摘要任务版本已经过期");
        }

        // 旧摘要只标记为已替代，原始消息和历史摘要始终保留用于回放。
        MemorySummaryEntity previousSummary = summaryRepository
                .findFirstByTopicIdAndStatusOrderByVersionNoDesc(
                        task.getTopicId(), MemorySummaryStatus.ACTIVE)
                .orElse(null);
        if (previousSummary != null) {
            previousSummary.supersede(now);
        }
        int sourceMessageCount = messageRepository
                .findByTopicIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        task.getTopicId(), task.getFromSequence(), task.getThroughSequence())
                .size();
        MemorySummaryEntity newSummary = MemorySummaryEntity.active(
                task.getConversationId(),
                task.getTopicId(),
                task.getExpectedSummaryVersion(),
                task.getFromSequence(),
                task.getThroughSequence(),
                result.summaryContent(),
                result.structuredState(),
                sourceMessageCount,
                result.providerId(),
                result.candidateId(),
                result.modelId(),
                now);
        summaryRepository.save(newSummary);
        topic.applySummary(
                task.getExpectedSummaryVersion(),
                task.getThroughSequence(),
                result.shortSummary(),
                result.structuredState(),
                now);
        task.succeed(now);
        return newSummary;
    }

    /**
     * 记录摘要处理失败并进入延迟重试或最终失败。
     *
     * @param taskId 任务标识
     * @param category 稳定失败分类
     * @param safeMessage 已脱敏失败摘要
     */
    @Transactional
    public void fail(String taskId, String category, String safeMessage) {
        SummaryTaskEntity task = taskRepository.findLockedById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("摘要任务不存在"));

        // 状态机根据当前尝试次数决定重试等待或最终失败。
        task.fail(category, safeMessage, clock.instant(), properties.summaryRetryDelay());
    }

    /**
     * 校验会话属于当前用户。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     */
    private void requireOwnedConversation(String userId, String conversationId) {
        ConversationEntity conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
    }

    /**
     * 摘要模型工作输入。
     *
     * @param taskId 任务标识
     * @param conversationId 会话标识
     * @param topicId 主题标识
     * @param expectedSummaryVersion 期望摘要版本
     * @param previousSummary 上一完整摘要
     * @param previousStructuredState 上一结构化状态
     * @param messages 本次新增原始消息
     */
    public record SummaryWorkItem(
            String taskId,
            String conversationId,
            String topicId,
            int expectedSummaryVersion,
            String previousSummary,
            String previousStructuredState,
            List<SummarySourceMessage> messages) {
    }

    /**
     * 摘要来源消息。
     *
     * @param messageId 消息标识
     * @param sequenceNo 会话消息序号
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
     * @param summaryContent 可替代旧摘要的完整摘要
     * @param shortSummary 主题路由短摘要
     * @param structuredState 结构化业务状态 JSON
     * @param providerId 实际模型平台标识
     * @param candidateId 实际候选模型标识
     * @param modelId 实际平台模型标识
     */
    public record SummaryGenerationResult(
            String summaryContent,
            String shortSummary,
            String structuredState,
            String providerId,
            String candidateId,
            String modelId) {
    }
}
