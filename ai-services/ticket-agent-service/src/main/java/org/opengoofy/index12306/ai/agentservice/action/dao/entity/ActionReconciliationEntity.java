package org.opengoofy.index12306.ai.agentservice.action.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.ai.agentservice.action.enums.ActionReconciliationStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.AgentBaseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 同时保存 UNKNOWN 操作的事务 Outbox 事件和 MQ 消费领取状态。
 */
@Getter
@TableName("t_agent_action_reconciliation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionReconciliationEntity extends AgentBaseEntity {

    /**
     * 待核对结果的操作草案标识。
     */
    private String actionId;

    /**
     * 对账 Outbox 事件版本，用于过滤过期 MQ 消息。
     */
    private long eventVersion;

    /**
     * 对账任务当前状态。
     */
    private ActionReconciliationStatus status;

    /**
     * 当前事件已经执行的对账次数。
     */
    private int attemptCount;

    /**
     * 自动查询下游结果允许的最大次数。
     */
    private int maxAttempts;

    /**
     * 对账失败后允许再次发布的最早时间。
     */
    private Instant nextRetryAt;

    /**
     * 当前持有对账租约的消费实例标识。
     */
    private String leaseOwner;

    /**
     * 当前对账消费租约的截止时间。
     */
    private Instant leaseUntil;

    /**
     * Inbox 幂等领取使用的稳定消费者名称。
     */
    private String consumerName;

    /**
     * RocketMQ 返回的消息标识。
     */
    private String mqMessageId;

    /**
     * 当前事件成功发布到 RocketMQ 的时间。
     */
    private Instant publishedAt;

    /**
     * 最近一次对账失败的稳定分类。
     */
    private String failureCategory;

    /**
     * 最近一次对账失败的安全限长说明。
     */
    private String failureMessage;

    /**
     * 最近一次对账执行开始时间。
     */
    private Instant startedAt;

    /**
     * 对账任务进入成功或失败终态的时间。
     */
    private Instant finishedAt;

    private ActionReconciliationEntity(
            String actionId,
            int maxAttempts,
            Instant now) {
        super(now);
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.eventVersion = 1L;
        this.status = ActionReconciliationStatus.PENDING;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 在 UNKNOWN 状态事务内创建待发布对账事件。
     *
     * @param actionId 操作草案标识
     * @param maxAttempts 最大查询次数
     * @param now 创建时间
     * @return 待发布对账任务
     */
    public static ActionReconciliationEntity pending(
            String actionId,
            int maxAttempts,
            Instant now) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("最大对账次数必须大于零");
        }
        // actionId 唯一约束保证同一真实写操作只产生一个对账任务。
        return new ActionReconciliationEntity(actionId, maxAttempts, now);
    }

    /**
     * 记录 RocketMQ 已接收当前事件。
     *
     * @param messageId RocketMQ 消息标识
     * @param now 发布时间
     */
    public void published(String messageId, Instant now) {
        if (status != ActionReconciliationStatus.PENDING) {
            return;
        }
        this.status = ActionReconciliationStatus.PUBLISHED;
        this.mqMessageId = Objects.requireNonNull(messageId, "messageId");
        this.publishedAt = now;
        touch(now);
    }

    /**
     * 幂等领取当前事件版本，并记录消费者租约作为 Inbox 处理凭证。
     *
     * @param messageEventVersion MQ 事件版本
     * @param workerId 消费实例标识
     * @param inboxConsumerName 稳定消费者名称
     * @param now 领取时间
     * @param leaseDuration 消费租约
     * @return 是否成功领取
     */
    public boolean claim(
            long messageEventVersion,
            String workerId,
            String inboxConsumerName,
            Instant now,
            Duration leaseDuration) {
        if (messageEventVersion != eventVersion
                || (status != ActionReconciliationStatus.PENDING
                && status != ActionReconciliationStatus.PUBLISHED)) {
            return false;
        }
        if (attemptCount >= maxAttempts) {
            this.status = ActionReconciliationStatus.FAILED;
            this.finishedAt = now;
            touch(now);
            return false;
        }
        // 状态、消费者名和租约一起提交，重复 MQ 消息只能观察到已经领取的 Inbox 状态。
        this.status = ActionReconciliationStatus.RUNNING;
        this.attemptCount++;
        this.leaseOwner = Objects.requireNonNull(workerId, "workerId");
        this.leaseUntil = now.plus(leaseDuration);
        this.consumerName = Objects.requireNonNull(inboxConsumerName, "inboxConsumerName");
        this.startedAt = now;
        this.nextRetryAt = null;
        touch(now);
        return true;
    }

    /**
     * 标记本次对账已经得到确定结果。
     *
     * @param now 完成时间
     */
    public void succeed(Instant now) {
        requireRunning();
        this.status = ActionReconciliationStatus.SUCCEEDED;
        clearLease();
        this.failureCategory = null;
        this.failureMessage = null;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 记录暂时无法得到确定结果，并安排数据库 Outbox 重新发布。
     *
     * @param category 失败分类
     * @param message 安全失败说明
     * @param now 失败时间
     * @param retryDelay 基础重试间隔
     * @return 是否仍允许重试
     */
    public boolean retry(
            String category,
            String message,
            Instant now,
            Duration retryDelay) {
        requireRunning();
        this.failureCategory = Objects.requireNonNull(category, "category");
        this.failureMessage = sanitize(message);
        clearLease();
        if (attemptCount >= maxAttempts) {
            // 达到上限后保持 Agent 操作为 UNKNOWN，只停止自动对账，禁止盲目重放写操作。
            this.status = ActionReconciliationStatus.FAILED;
            this.finishedAt = now;
            this.nextRetryAt = null;
            touch(now);
            return false;
        }
        this.status = ActionReconciliationStatus.RETRY_WAIT;
        this.nextRetryAt = now.plus(retryDelay.multipliedBy(attemptCount));
        touch(now);
        return true;
    }

    /**
     * 把消费宕机或到期重试事件恢复为待发布状态。
     *
     * @param now 当前时间
     * @return 是否发生状态恢复
     */
    public boolean recoverForRepublish(Instant now) {
        boolean leaseExpired = status == ActionReconciliationStatus.RUNNING
                && leaseUntil != null && !now.isBefore(leaseUntil);
        boolean retryDue = status == ActionReconciliationStatus.RETRY_WAIT
                && nextRetryAt != null && !now.isBefore(nextRetryAt);
        if (!leaseExpired && !retryDue) {
            return false;
        }
        if (attemptCount >= maxAttempts) {
            this.status = ActionReconciliationStatus.FAILED;
            clearLease();
            this.nextRetryAt = null;
            this.finishedAt = now;
            touch(now);
            return true;
        }
        // 复用同一 eventVersion，消费者对重复消息仍只认领同一持久化事件。
        this.status = ActionReconciliationStatus.PENDING;
        clearLease();
        this.nextRetryAt = null;
        this.mqMessageId = null;
        this.publishedAt = null;
        touch(now);
        return true;
    }

    /**
     * 清除已经结束或失效的消费者租约。
     */
    private void clearLease() {
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    /**
     * 校验只有已领取事件能够提交消费结果。
     */
    private void requireRunning() {
        if (status != ActionReconciliationStatus.RUNNING) {
            throw new IllegalStateException("对账任务不处于运行状态");
        }
    }

    /**
     * 将失败说明转换为单行限长文本。
     *
     * @param value 原始说明
     * @return 最多 512 字符的安全说明
     */
    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }
}
