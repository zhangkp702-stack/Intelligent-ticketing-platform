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

    /**
     * 当前持有真实写执行权的实例标识。
     */
    private String leaseOwner;

    /**
     * 当前执行租约的截止时间。
     */
    private Instant leaseUntil;

    /**
     * 每次重新领取都会递增的隔离令牌。
     */
    private long fencingToken;

    /**
     * 最近一次成功续租时间。
     */
    private Instant lastHeartbeatAt;

    /**
     * 真实写执行权被领取的累计次数。
     */
    private int attemptCount;

    /**
     * 结果未知后允许再次查询下游事实的时间。
     */
    private Instant nextReconcileAt;

    private ActionExecutionEntity(
            String actionId,
            String requestId,
            String idempotencyKey,
            Instant now) {
        super(now);
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.outcome = ActionExecutionOutcome.QUEUED;
        // 兼容既有非空列；真正取得执行权时会覆盖为实际开始时间。
        this.startedAt = now;
    }

    /**
     * 创建已消费确认机会但尚未取得真实写权限的排队记录。
     *
     * @param actionId 操作草案标识
     * @param requestId 确认请求标识
     * @param idempotencyKey 确认幂等键
     * @param now 开始时间
     * @return 新执行记录
     */
    public static ActionExecutionEntity queue(
            String actionId,
            String requestId,
            String idempotencyKey,
            Instant now) {
        // 一条草案只允许创建一条执行记录，数据库唯一约束提供最终并发保护。
        return new ActionExecutionEntity(actionId, requestId, idempotencyKey, now);
    }

    /**
     * 使用新的 fencing token 领取真实写执行权。
     *
     * @param owner 执行实例标识
     * @param leaseDeadline 租约截止时间
     * @param now 领取时间
     * @return 本次领取获得的 fencing token
     */
    public long claim(String owner, Instant leaseDeadline, Instant now) {
        if (outcome != ActionExecutionOutcome.QUEUED) {
            throw new IllegalStateException("只有 QUEUED 执行可以领取");
        }
        // 领取时递增令牌，任何旧实例随后提交的心跳或终态都会被拒绝。
        this.leaseOwner = Objects.requireNonNull(owner, "owner");
        this.leaseUntil = Objects.requireNonNull(leaseDeadline, "leaseDeadline");
        this.fencingToken += 1L;
        this.lastHeartbeatAt = now;
        this.attemptCount += 1;
        this.outcome = ActionExecutionOutcome.STARTED;
        this.startedAt = now;
        touch(now);
        return fencingToken;
    }

    /**
     * 在仍持有执行权时延长租约。
     *
     * @param owner 执行实例标识
     * @param token fencing token
     * @param leaseDeadline 新租约截止时间
     * @param now 心跳时间
     */
    public void heartbeat(String owner, long token, Instant leaseDeadline, Instant now) {
        requireOwner(owner, token);
        this.leaseUntil = Objects.requireNonNull(leaseDeadline, "leaseDeadline");
        this.lastHeartbeatAt = now;
        touch(now);
    }

    /**
     * 记录真实业务调用成功。
     *
     * @param owner 执行实例标识
     * @param token fencing token
     * @param reference 业务结果引用
     * @param fingerprint 脱敏响应指纹
     * @param now 完成时间
     */
    public void succeed(
            String owner,
            long token,
            String reference,
            String fingerprint,
            Instant now) {
        requireOwner(owner, token);
        this.resultReference = reference;
        this.responseFingerprint = fingerprint;
        this.outcome = ActionExecutionOutcome.SUCCEEDED;
        this.finishedAt = now;
        clearLease();
        touch(now);
    }

    /**
     * 记录明确失败的业务写调用。
     *
     * @param owner 执行实例标识
     * @param token fencing token
     * @param category 稳定失败分类
     * @param type 异常类型
     * @param now 完成时间
     */
    public void fail(String owner, long token, String category, String type, Instant now) {
        requireOwner(owner, token);
        finish(ActionExecutionOutcome.FAILED, category, type, now, now);
    }

    /**
     * 记录结果不确定且禁止自动重试的业务写调用。
     *
     * @param owner 执行实例标识
     * @param token fencing token
     * @param category 稳定失败分类
     * @param type 异常类型
     * @param now 完成时间
     */
    public void markUnknown(String owner, long token, String category, String type, Instant now) {
        requireOwner(owner, token);
        finish(ActionExecutionOutcome.UNKNOWN, category, type, null, now);
    }

    /**
     * 将租约已经过期的执行转为结果未知，后续只能查询下游权威事实。
     *
     * @param category 稳定失败分类
     * @param now 恢复时间
     */
    public void recoverExpired(String category, Instant now) {
        if (outcome != ActionExecutionOutcome.STARTED
                || leaseUntil == null
                || now.isBefore(leaseUntil)) {
            throw new IllegalStateException("操作执行租约尚未过期");
        }
        // 宕机点可能位于下游成功之后，恢复器不能重新执行真实写操作。
        finish(ActionExecutionOutcome.UNKNOWN, category, null, null, now);
    }

    /**
     * 结束长期未领取的排队记录，此状态能够证明真实写调用尚未开始。
     *
     * @param category 稳定失败分类
     * @param now 恢复时间
     */
    public void abandonQueued(String category, Instant now) {
        if (outcome != ActionExecutionOutcome.QUEUED) {
            throw new IllegalStateException("操作执行记录不处于排队状态");
        }
        // 未产生 STARTED 和租约意味着执行器从未获得调用下游的权限，可以安全结束。
        this.failureCategory = Objects.requireNonNull(category, "category");
        this.outcome = ActionExecutionOutcome.FAILED;
        this.finishedAt = now;
        touch(now);
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
        this.nextReconcileAt = finalOutcome == ActionExecutionOutcome.UNKNOWN ? now : null;
        clearLease();
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
     * 校验终态或心跳写入仍属于当前租约持有者。
     *
     * @param owner 执行实例标识
     * @param token fencing token
     */
    private void requireOwner(String owner, long token) {
        requireStarted();
        if (!Objects.equals(leaseOwner, owner) || fencingToken != token) {
            throw new IllegalStateException("操作执行权已经失效");
        }
    }

    /**
     * 清除终态不再需要的租约字段。
     */
    private void clearLease() {
        this.leaseOwner = null;
        this.leaseUntil = null;
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
