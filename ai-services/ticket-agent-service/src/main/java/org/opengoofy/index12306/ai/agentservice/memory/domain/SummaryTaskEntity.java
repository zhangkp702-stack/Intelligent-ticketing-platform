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
 * 可重试、可租约恢复的异步主题摘要任务。
 */
@Getter
@Entity
@Table(name = "t_agent_summary_task")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SummaryTaskEntity extends AgentBaseEntity {

    @Column(name = "conversation_id", nullable = false, length = 32)
    private String conversationId;

    @Column(name = "topic_id", nullable = false, length = 32)
    private String topicId;

    @Column(name = "from_sequence", nullable = false)
    private long fromSequence;

    @Column(name = "through_sequence", nullable = false)
    private long throughSequence;

    @Column(name = "expected_summary_version", nullable = false)
    private int expectedSummaryVersion;

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
            String topicId,
            long fromSequence,
            long throughSequence,
            int expectedSummaryVersion,
            int maxAttempts,
            Instant now) {
        super(now);
        if (fromSequence > throughSequence) {
            throw new IllegalArgumentException("摘要起始消息序号不能大于结束序号");
        }
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.topicId = Objects.requireNonNull(topicId, "topicId");
        this.fromSequence = fromSequence;
        this.throughSequence = throughSequence;
        this.expectedSummaryVersion = expectedSummaryVersion;
        this.maxAttempts = maxAttempts;
        this.status = SummaryTaskStatus.PENDING;
    }

    /**
     * 创建一个覆盖确定消息范围的待执行摘要任务。
     *
     * @param conversationId 会话标识
     * @param topicId 主题标识
     * @param fromSequence 起始消息序号
     * @param throughSequence 结束消息序号
     * @param expectedSummaryVersion 任务成功时应写入的摘要版本
     * @param maxAttempts 最大尝试次数
     * @param now 创建时间
     * @return 新摘要任务
     */
    public static SummaryTaskEntity pending(
            String conversationId,
            String topicId,
            long fromSequence,
            long throughSequence,
            int expectedSummaryVersion,
            int maxAttempts,
            Instant now) {
        // 消息范围和期望版本在入队时冻结，后续完成阶段据此阻止并发覆盖。
        return new SummaryTaskEntity(
                conversationId, topicId, fromSequence, throughSequence,
                expectedSummaryVersion, maxAttempts, now);
    }

    /**
     * 判断任务在指定时间是否可以由执行器领取。
     *
     * @param now 当前时间
     * @return 待执行、到期重试或租约过期时返回 {@code true}
     */
    public boolean isDispatchable(Instant now) {
        if (status == SummaryTaskStatus.PENDING) {
            return true;
        }
        if (status == SummaryTaskStatus.RETRY_WAIT) {
            return nextRetryAt == null || !now.isBefore(nextRetryAt);
        }
        return status == SummaryTaskStatus.RUNNING
                && leaseUntil != null
                && !now.isBefore(leaseUntil);
    }

    /**
     * 由指定工作节点领取任务并建立有限租约。
     *
     * @param workerId 工作节点标识
     * @param now 领取时间
     * @param leaseDuration 租约时长
     */
    public void claim(String workerId, Instant now, Duration leaseDuration) {
        if (!isDispatchable(now)) {
            throw new IllegalStateException("摘要任务当前不可领取");
        }
        if (attemptCount >= maxAttempts) {
            throw new IllegalStateException("摘要任务已达到最大尝试次数");
        }
        // 每次重新领取都增加尝试次数，租约过期后其他实例可以安全接管。
        this.status = SummaryTaskStatus.RUNNING;
        this.attemptCount++;
        this.leaseOwner = Objects.requireNonNull(workerId, "workerId");
        this.leaseUntil = now.plus(leaseDuration);
        this.nextRetryAt = null;
        this.startedAt = now;
        touch(now);
    }

    /**
     * 在摘要和主题版本提交成功后完成任务。
     *
     * @param now 完成时间
     */
    public void succeed(Instant now) {
        requireRunning();
        // 任务完成后清理租约，保留尝试次数用于运维审计。
        this.status = SummaryTaskStatus.SUCCEEDED;
        this.finishedAt = now;
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.failureCategory = null;
        this.failureMessage = null;
        touch(now);
    }

    /**
     * 记录一次失败，并根据剩余尝试次数进入延迟重试或最终失败。
     *
     * @param category 稳定失败分类
     * @param message 已脱敏的失败摘要
     * @param now 失败时间
     * @param retryDelay 基础重试等待时间
     */
    public void fail(String category, String message, Instant now, Duration retryDelay) {
        requireRunning();
        this.failureCategory = category;
        this.failureMessage = sanitizeFailureMessage(message);
        this.leaseOwner = null;
        this.leaseUntil = null;

        // 达到最大次数后进入最终失败，否则按尝试次数线性延长下一次重试时间。
        if (attemptCount >= maxAttempts) {
            this.status = SummaryTaskStatus.FAILED;
            this.finishedAt = now;
            this.nextRetryAt = null;
        } else {
            this.status = SummaryTaskStatus.RETRY_WAIT;
            this.nextRetryAt = now.plus(retryDelay.multipliedBy(attemptCount));
        }
        touch(now);
    }

    /**
     * 校验任务当前由某个执行器持有。
     */
    private void requireRunning() {
        if (status != SummaryTaskStatus.RUNNING) {
            throw new IllegalStateException("只有运行中的摘要任务可以结束");
        }
    }

    /**
     * 清理换行并限制失败说明长度，避免异常正文或敏感输入进入审计表。
     *
     * @param message 原始失败说明
     * @return 最长 512 字符的单行失败摘要
     */
    private String sanitizeFailureMessage(String message) {
        if (message == null) {
            return null;
        }
        // 数据库只保存简短诊断说明，完整堆栈继续交给受控日志系统。
        String sanitized = message.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
    }
}
