package org.opengoofy.index12306.ai.agentservice.action.dao.entity;

import org.opengoofy.index12306.ai.agentservice.action.enums.ActionExecutionOutcome;


import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName("t_agent_action_execution")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionExecutionEntity extends AgentBaseEntity {

    /**
     * 本次执行对应的操作草案标识。
     */
    private String actionId;

    /**
     * 触发用户确认的请求标识。
     */
    private String requestId;

    /**
     * 下游真实业务写调用使用的幂等键。
     */
    private String idempotencyKey;

    /**
     * 本次业务写调用或后续对账的结果状态。
     */
    private ActionExecutionOutcome outcome;

    /**
     * 本次真实业务写调用开始时间。
     */
    private Instant startedAt;

    /**
     * 执行得到明确成功或失败结果的时间。
     */
    private Instant finishedAt;

    /**
     * 执行成功后返回的订单号等业务引用。
     */
    private String resultReference;

    /**
     * 下游脱敏响应的内容指纹。
     */
    private String responseFingerprint;

    /**
     * 执行失败或结果不确定时记录的稳定分类。
     */
    private String failureCategory;

    /**
     * 执行异常的类型名称，不包含异常正文。
     */
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
        finish(ActionExecutionOutcome.FAILED, category, type, now, now);
    }

    /**
     * 记录结果不确定且禁止自动重试的业务写调用。
     *
     * @param category 稳定失败分类
     * @param type 异常类型
     * @param now 完成时间
     */
    public void markUnknown(String category, String type, Instant now) {
        finish(ActionExecutionOutcome.UNKNOWN, category, type, null, now);
    }

    /**
     * 在对账事件被唯一领取后进入查询状态。
     *
     * @param now 领取时间
     */
    public void beginReconciliation(Instant now) {
        if (outcome != ActionExecutionOutcome.UNKNOWN) {
            throw new IllegalStateException("只有 UNKNOWN 执行可以开始对账");
        }
        this.outcome = ActionExecutionOutcome.RECONCILING;
        this.finishedAt = null;
        touch(now);
    }

    /**
     * 使用下游权威结果完成成功对账。
     *
     * @param reference 业务结果引用
     * @param fingerprint 脱敏结果指纹
     * @param now 完成时间
     */
    public void reconcileSucceeded(String reference, String fingerprint, Instant now) {
        requireReconciling();
        this.resultReference = reference;
        this.responseFingerprint = fingerprint;
        this.failureCategory = null;
        this.exceptionType = null;
        this.outcome = ActionExecutionOutcome.SUCCEEDED;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 使用下游明确失败状态结束对账。
     *
     * @param category 稳定失败分类
     * @param now 完成时间
     */
    public void reconcileFailed(String category, Instant now) {
        requireReconciling();
        this.failureCategory = Objects.requireNonNull(category, "category");
        this.outcome = ActionExecutionOutcome.FAILED;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 查询仍处理中或暂时失败时回到 UNKNOWN。
     *
     * @param now 本次查询结束时间
     */
    public void reconciliationPending(Instant now) {
        requireReconciling();
        this.outcome = ActionExecutionOutcome.UNKNOWN;
        this.finishedAt = null;
        touch(now);
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
            Instant finishedAt,
            Instant now) {
        requireStarted();
        this.failureCategory = Objects.requireNonNull(category, "category");
        this.exceptionType = type;
        this.outcome = finalOutcome;
        this.finishedAt = finishedAt;
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

    /**
     * 校验执行审计已经进入对账状态。
     */
    private void requireReconciling() {
        if (outcome != ActionExecutionOutcome.RECONCILING) {
            throw new IllegalStateException("操作执行记录不处于对账状态");
        }
    }
}
