package org.opengoofy.index12306.ai.agentservice.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 合并同一会话摘要请求并记录 MQ 发布、领取和重试状态。
 */
@Getter
@Entity
@Table(name = "t_agent_summary_task")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SummaryTaskEntity extends AgentBaseEntity {

    @Column(name = "conversation_id", nullable = false, unique = true, length = 32)
    private String conversationId;

    @Column(name = "desired_through_sequence", nullable = false)
    private long desiredThroughSequence;

    @Column(name = "processing_through_sequence")
    private Long processingThroughSequence;

    @Column(name = "expected_summary_version", nullable = false)
    private int expectedSummaryVersion;

    @Column(name = "event_version", nullable = false)
    private long eventVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SummaryTaskStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "mq_message_id", length = 128)
    private String mqMessageId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "failure_category", length = 64)
    private String failureCategory;

    @Column(name = "failure_message", length = 512)
    private String failureMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    private SummaryTaskEntity(
            String conversationId,
            long desiredThroughSequence,
            int expectedSummaryVersion,
            int maxAttempts,
            Instant now) {
        super(now);
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.desiredThroughSequence = desiredThroughSequence;
        this.expectedSummaryVersion = expectedSummaryVersion;
        this.maxAttempts = maxAttempts;
        this.eventVersion = 1;
        this.status = SummaryTaskStatus.PENDING;
    }

    /**
     * 创建会话首个待发布摘要任务。
     *
     * @param conversationId 会话标识
     * @param desiredThroughSequence 期望摘要覆盖边界
     * @param expectedSummaryVersion 当前摘要版本
     * @param maxAttempts 最大尝试次数
     * @param now 创建时间
     * @return 待发布任务
     */
    public static SummaryTaskEntity pending(
            String conversationId,
            long desiredThroughSequence,
            int expectedSummaryVersion,
            int maxAttempts,
            Instant now) {
        return new SummaryTaskEntity(
                conversationId, desiredThroughSequence, expectedSummaryVersion, maxAttempts, now);
    }

    /**
     * 合并同一会话的新摘要边界，避免连续对话创建大量任务和 MQ 消息。
     *
     * @param throughSequence 新的摘要目标边界
     * @param summaryVersion 当前摘要版本
     * @param now 更新时间
     */
    public void request(long throughSequence, int summaryVersion, Instant now) {
        if (throughSequence <= desiredThroughSequence && status != SummaryTaskStatus.FAILED) {
            return;
        }
        this.desiredThroughSequence = Math.max(desiredThroughSequence, throughSequence);
        if (status == SummaryTaskStatus.SUCCEEDED || status == SummaryTaskStatus.FAILED) {
            // 已结束任务在产生足够新消息后复用同一行，并生成新的事件版本。
            this.expectedSummaryVersion = summaryVersion;
            this.eventVersion++;
            this.attemptCount = 0;
            this.status = SummaryTaskStatus.PENDING;
            this.finishedAt = null;
            this.failureCategory = null;
            this.failureMessage = null;
            this.mqMessageId = null;
            this.publishedAt = null;
        }
        touch(now);
    }

    /**
     * 记录 RocketMQ 已持久化接收当前事件。
     *
     * @param messageId RocketMQ 消息标识
     * @param now 发布时间
     */
    public void published(String messageId, Instant now) {
        if (status != SummaryTaskStatus.PENDING) {
            return;
        }
        this.status = SummaryTaskStatus.PUBLISHED;
        this.mqMessageId = messageId;
        this.publishedAt = now;
        touch(now);
    }

    /**
     * 领取与当前事件版本一致的任务，并冻结本次处理边界。
     *
     * @param messageEventVersion MQ 消息携带的事件版本
     * @param workerId 消费节点标识
     * @param now 领取时间
     * @param leaseDuration 租约时长
     * @return 是否成功领取；过期或重复消息返回 false
     */
    public boolean claim(long messageEventVersion, String workerId, Instant now, Duration leaseDuration) {
        if (messageEventVersion != eventVersion || status == SummaryTaskStatus.SUCCEEDED
                || status == SummaryTaskStatus.FAILED) {
            return false;
        }
        if (status == SummaryTaskStatus.RUNNING && leaseUntil != null && now.isBefore(leaseUntil)) {
            return false;
        }
        if (attemptCount >= maxAttempts) {
            this.status = SummaryTaskStatus.FAILED;
            this.finishedAt = now;
            touch(now);
            return false;
        }
        // 冻结当前目标边界，模型调用期间产生的新消息只更新 desiredThroughSequence。
        this.processingThroughSequence = desiredThroughSequence;
        this.status = SummaryTaskStatus.RUNNING;
        this.attemptCount++;
        this.leaseOwner = Objects.requireNonNull(workerId, "workerId");
        this.leaseUntil = now.plus(leaseDuration);
        this.nextRetryAt = null;
        this.startedAt = now;
        touch(now);
        return true;
    }

    /**
     * 完成本次边界；处理期间有新消息时生成下一版本待发布事件。
     *
     * @param newSummaryVersion 成功提交后的摘要版本
     * @param now 完成时间
     */
    public void succeed(int newSummaryVersion, Instant now) {
        requireRunning();
        this.expectedSummaryVersion = newSummaryVersion;
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.failureCategory = null;
        this.failureMessage = null;
        if (processingThroughSequence != null && desiredThroughSequence > processingThroughSequence) {
            // 后续消息继续复用任务行，通过新的事件版本重新发布。
            this.status = SummaryTaskStatus.PENDING;
            this.eventVersion++;
            this.attemptCount = 0;
            this.mqMessageId = null;
            this.publishedAt = null;
        } else {
            this.status = SummaryTaskStatus.SUCCEEDED;
            this.finishedAt = now;
        }
        touch(now);
    }

    /**
     * 记录一次消费失败，保留同一事件版本供 RocketMQ 重投。
     *
     * @param category 失败分类
     * @param message 脱敏失败说明
     * @param now 失败时间
     * @param retryDelay 重试等待时间
     * @return 是否仍允许 RocketMQ 重试
     */
    public boolean fail(String category, String message, Instant now, Duration retryDelay) {
        requireRunning();
        this.failureCategory = category;
        this.failureMessage = sanitizeFailureMessage(message);
        this.leaseOwner = null;
        this.leaseUntil = null;
        if (attemptCount >= maxAttempts) {
            this.status = SummaryTaskStatus.FAILED;
            this.finishedAt = now;
            this.nextRetryAt = null;
            touch(now);
            return false;
        }
        this.status = SummaryTaskStatus.RETRY_WAIT;
        this.nextRetryAt = now.plus(retryDelay.multipliedBy(attemptCount));
        touch(now);
        return true;
    }

    /**
     * 将到期重试或租约过期的任务恢复为待发布，避免消费者宕机后任务永久卡住。
     *
     * @param now 当前时间
     * @return 是否恢复了任务状态
     */
    public boolean recoverForRepublish(Instant now) {
        boolean retryDue = status == SummaryTaskStatus.RETRY_WAIT
                && nextRetryAt != null && !now.isBefore(nextRetryAt);
        boolean leaseExpired = status == SummaryTaskStatus.RUNNING
                && leaseUntil != null && !now.isBefore(leaseUntil);
        if (!retryDue && !leaseExpired) {
            return false;
        }
        if (attemptCount >= maxAttempts) {
            // 最后一次执行丢失或失败时直接终止，防止恢复扫描形成无限重试。
            this.status = SummaryTaskStatus.FAILED;
            this.finishedAt = now;
            this.nextRetryAt = null;
            this.leaseOwner = null;
            this.leaseUntil = null;
            touch(now);
            return true;
        }
        // 保留事件版本和尝试次数，重新发布后仍由同一幂等键继续处理。
        this.status = SummaryTaskStatus.PENDING;
        this.nextRetryAt = null;
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.mqMessageId = null;
        this.publishedAt = null;
        touch(now);
        return true;
    }

    /**
     * 校验只有运行中的任务能够完成或失败。
     */
    private void requireRunning() {
        if (status != SummaryTaskStatus.RUNNING) {
            throw new IllegalStateException("只有运行中的摘要任务可以结束");
        }
    }

    /**
     * 清理并截断失败说明，避免日志正文进入任务表。
     *
     * @param message 原始失败说明
     * @return 最长 512 字符的单行文本
     */
    private String sanitizeFailureMessage(String message) {
        if (message == null) {
            return null;
        }
        String sanitized = message.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
    }
}
