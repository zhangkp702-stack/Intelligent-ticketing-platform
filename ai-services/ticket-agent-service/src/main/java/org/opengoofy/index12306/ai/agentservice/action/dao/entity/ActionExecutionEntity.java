package org.opengoofy.index12306.ai.agentservice.action.dao.entity;

import org.opengoofy.index12306.ai.agentservice.action.enums.ActionExecutionOutcome;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.AgentBaseEntity;

import java.time.Instant;
import java.util.Objects;

/**
 * 对一次受确认保护的真实业务写调用进行独立审计。
 */
@Getter
@Entity
@Table(name = "t_agent_action_execution")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionExecutionEntity extends AgentBaseEntity {

    @Column(name = "action_id", nullable = false, length = 32)
    private String actionId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private ActionExecutionOutcome outcome;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "result_reference", length = 128)
    private String resultReference;

    @Column(name = "response_fingerprint", length = 64)
    private String responseFingerprint;

    @Column(name = "failure_category", length = 64)
    private String failureCategory;

    @Column(name = "exception_type", length = 256)
    private String exceptionType;

    private ActionExecutionEntity(
            String actionId,
            String requestId,
            String idempotencyKey,
            Instant now) {
        super(now);
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.outcome = ActionExecutionOutcome.STARTED;
        this.startedAt = now;
    }

    /**
     * 创建已领取确认机会但尚未得到下游结果的执行记录。
     *
     * @param actionId 操作草案标识
     * @param requestId 确认请求标识
     * @param idempotencyKey 确认幂等键
     * @param now 开始时间
     * @return 新执行记录
     */
    public static ActionExecutionEntity start(
            String actionId,
            String requestId,
            String idempotencyKey,
            Instant now) {
        // 一条草案只允许创建一条执行记录，数据库唯一约束提供最终并发保护。
        return new ActionExecutionEntity(actionId, requestId, idempotencyKey, now);
    }

    /**
     * 记录真实业务调用成功。
     *
     * @param reference 业务结果引用
     * @param fingerprint 脱敏响应指纹
     * @param now 完成时间
     */
    public void succeed(String reference, String fingerprint, Instant now) {
        requireStarted();
        this.resultReference = reference;
        this.responseFingerprint = fingerprint;
        this.outcome = ActionExecutionOutcome.SUCCEEDED;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 记录明确失败的业务写调用。
     *
     * @param category 稳定失败分类
     * @param type 异常类型
     * @param now 完成时间
     */
    public void fail(String category, String type, Instant now) {
        finish(ActionExecutionOutcome.FAILED, category, type, now);
    }

    /**
     * 记录结果不确定且禁止自动重试的业务写调用。
     *
     * @param category 稳定失败分类
     * @param type 异常类型
     * @param now 完成时间
     */
    public void markUnknown(String category, String type, Instant now) {
        finish(ActionExecutionOutcome.UNKNOWN, category, type, now);
    }

    /**
     * 按指定终态结束失败执行记录。
     *
     * @param finalOutcome 失败或未知终态
     * @param category 稳定失败分类
     * @param type 异常类型
     * @param now 完成时间
     */
    private void finish(
            ActionExecutionOutcome finalOutcome,
            String category,
            String type,
            Instant now) {
        requireStarted();
        this.failureCategory = Objects.requireNonNull(category, "category");
        this.exceptionType = type;
        this.outcome = finalOutcome;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 校验执行记录只能结束一次。
     */
    private void requireStarted() {
        if (outcome != ActionExecutionOutcome.STARTED) {
            throw new IllegalStateException("操作执行记录已经结束");
        }
    }
}
