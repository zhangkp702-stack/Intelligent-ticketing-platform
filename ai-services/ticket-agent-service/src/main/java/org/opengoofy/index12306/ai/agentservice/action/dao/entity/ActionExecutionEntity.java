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

    private String actionId;

    private String requestId;

    private String idempotencyKey;

    private ActionExecutionOutcome outcome;

    private Instant startedAt;

    private Instant finishedAt;

    private String resultReference;

    private String responseFingerprint;

    private String failureCategory;

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
