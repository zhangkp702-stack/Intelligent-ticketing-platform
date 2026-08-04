package org.opengoofy.index12306.ai.agentservice.action.dao.entity;

import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;


import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.AgentBaseEntity;

import java.time.Instant;
import java.util.Objects;

/**
 * 模型只能创建、用户确认后才能进入执行态的高风险操作草案。
 */
@Getter
@TableName("t_agent_action_draft")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionDraftEntity extends AgentBaseEntity {

    /**
     * 操作草案所属的用户标识。
     */
    private String userId;

    /**
     * 操作草案所属的会话标识。
     */
    private String conversationId;

    /**
     * 创建操作草案的问答轮次标识。
     */
    private String turnId;

    /**
     * 需要用户确认的高风险操作类型。
     */
    private AgentActionType actionType;

    /**
     * 操作草案当前状态。
     */
    private AgentActionStatus status;

    /**
     * 经服务端规范化且不含敏感信息的操作参数 JSON。
     */
    private String payloadJson;

    /**
     * 操作参数指纹，用于校验确认内容未被替换。
     */
    private String payloadHash;

    /**
     * 用户确认操作的截止时间。
     */
    private Instant confirmationExpiresAt;

    /**
     * 确认机会被成功消费的时间。
     */
    private Instant confirmationConsumedAt;

    /**
     * 确认后创建的操作执行记录标识。
     */
    private String executionId;

    /**
     * 操作成功后保存的脱敏结果 JSON。
     */
    private String resultJson;

    /**
     * 订单号等可安全展示的业务结果引用。
     */
    private String resultReference;

    /**
     * 操作失败或结果不确定时记录的稳定分类。
     */
    private String failureCategory;

    /**
     * 操作进入明确终态的时间。
     */
    private Instant finishedAt;

    private ActionDraftEntity(
            String userId,
            String conversationId,
            String turnId,
            AgentActionType actionType,
            String payloadJson,
            String payloadHash,
            Instant confirmationExpiresAt,
            Instant now) {
        super(now);
        this.userId = Objects.requireNonNull(userId, "userId");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.turnId = Objects.requireNonNull(turnId, "turnId");
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.status = AgentActionStatus.AWAITING_CONFIRMATION;
        this.payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
        this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash");
        this.confirmationExpiresAt = Objects.requireNonNull(confirmationExpiresAt, "confirmationExpiresAt");
    }

    /**
     * 创建等待用户确认的购票草案。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param turnId 创建草案的轮次标识
     * @param payloadJson 规范化购票参数 JSON
     * @param payloadHash 参数指纹
     * @param confirmationExpiresAt 确认截止时间
     * @param now 创建时间
     * @return 新购票草案
     */
    public static ActionDraftEntity createPurchase(
            String userId,
            String conversationId,
            String turnId,
            String payloadJson,
            String payloadHash,
            Instant confirmationExpiresAt,
            Instant now) {
        // 创建时仅保存不可执行草案，任何业务订单都尚未产生。
        return new ActionDraftEntity(
                userId, conversationId, turnId, AgentActionType.TICKET_PURCHASE,
                payloadJson, payloadHash, confirmationExpiresAt, now);
    }

    /**
     * 创建等待用户确认的通用高风险操作草案。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param turnId 创建草案的轮次标识
     * @param actionType 操作类型
     * @param payloadJson 规范化参数 JSON
     * @param payloadHash 参数指纹
     * @param confirmationExpiresAt 确认截止时间
     * @param now 创建时间
     * @return 新操作草案
     */
    public static ActionDraftEntity create(
            String userId,
            String conversationId,
            String turnId,
            AgentActionType actionType,
            String payloadJson,
            String payloadHash,
            Instant confirmationExpiresAt,
            Instant now) {
        // 所有高风险操作统一从待确认状态开始，草案创建本身不调用任何业务写接口。
        return new ActionDraftEntity(
                userId, conversationId, turnId, actionType,
                payloadJson, payloadHash, confirmationExpiresAt, now);
    }

    /**
     * 原子消费确认机会并进入等待执行器领取的排队状态。
     *
     * @param confirmedExecutionId 执行记录标识
     * @param now 用户确认时间
     */
    public void queueExecution(String confirmedExecutionId, Instant now) {
        if (status != AgentActionStatus.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("操作草案不处于待确认状态");
        }
        if (!now.isBefore(confirmationExpiresAt)) {
            expire(now);
            throw new IllegalStateException("操作确认已经过期");
        }
        // 状态变化和确认消费时间在同一数据库锁内提交，保证令牌只能成功使用一次。
        this.executionId = Objects.requireNonNull(confirmedExecutionId, "confirmedExecutionId");
        this.confirmationConsumedAt = now;
        this.status = AgentActionStatus.QUEUED;
        touch(now);
    }

    /**
     * 在执行记录成功取得数据库租约后进入执行中状态。
     *
     * @param now 领取执行权的时间
     */
    public void beginExecution(Instant now) {
        if (status != AgentActionStatus.QUEUED) {
            throw new IllegalStateException("操作草案不处于排队状态");
        }
        // 草案与执行审计在同一事务中迁移，避免页面状态领先于真实执行权。
        this.status = AgentActionStatus.EXECUTING;
        touch(now);
    }

    /**
     * 将确认事务后长期未被执行器领取的草案结束为明确失败。
     *
     * @param category 稳定失败分类
     * @param now 恢复时间
     */
    public void failQueued(String category, Instant now) {
        if (status != AgentActionStatus.QUEUED) {
            throw new IllegalStateException("操作草案不处于排队状态");
        }
        // QUEUED 尚未取得真实写权限，可以明确结束并允许用户重新生成草案。
        this.failureCategory = Objects.requireNonNull(category, "category");
        this.status = AgentActionStatus.FAILED;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 保存业务写调用成功后的脱敏结果。
     *
     * @param safeResultJson 脱敏结果 JSON
     * @param reference 业务结果引用，例如订单号
     * @param now 完成时间
     */
    public void succeed(String safeResultJson, String reference, Instant now) {
        requireExecuting();
        // 仅持久化 MCP 返回的白名单结果，不保存证件号等下游完整响应。
        this.resultJson = Objects.requireNonNull(safeResultJson, "safeResultJson");
        this.resultReference = reference;
        this.status = AgentActionStatus.SUCCEEDED;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 将明确未成功的业务拒绝记录为失败。
     *
     * @param category 稳定失败分类
     * @param now 完成时间
     */
    public void fail(String category, Instant now) {
        requireExecuting();
        // 失败状态不保存可能包含用户或平台敏感信息的异常正文。
        this.failureCategory = Objects.requireNonNull(category, "category");
        this.status = AgentActionStatus.FAILED;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 将无法判断下游是否成功的调用记录为待人工核对状态。
     *
     * @param category 稳定失败分类
     * @param now 完成时间
     */
    public void markUnknown(String category, Instant now) {
        requireExecuting();
        // 网络或超时后禁止自动重试购票，避免下游已成功时生成重复订单。
        this.failureCategory = Objects.requireNonNull(category, "category");
        this.status = AgentActionStatus.UNKNOWN;
        this.finishedAt = null;
        touch(now);
    }

    /**
     * 在 MQ 消费者获得持久化领取权后进入对账状态。
     *
     * @param now 领取时间
     */
    public void beginReconciliation(Instant now) {
        if (status != AgentActionStatus.UNKNOWN) {
            throw new IllegalStateException("只有 UNKNOWN 操作可以开始对账");
        }
        // RECONCILING 只表示查询下游事实，不允许再次调用真实写接口。
        this.status = AgentActionStatus.RECONCILING;
        touch(now);
    }

    /**
     * 使用下游持久化结果完成对账成功状态。
     *
     * @param safeResultJson 脱敏结果 JSON
     * @param reference 订单号等业务引用
     * @param now 完成时间
     */
    public void reconcileSucceeded(String safeResultJson, String reference, Instant now) {
        requireReconciling();
        // 对账结果来自 ticket-service 操作事实表，不接受原网络调用的迟到正文。
        this.resultJson = Objects.requireNonNull(safeResultJson, "safeResultJson");
        this.resultReference = reference;
        this.failureCategory = null;
        this.status = AgentActionStatus.SUCCEEDED;
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
        this.status = AgentActionStatus.FAILED;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 查询暂未得到确定结果时回到 UNKNOWN 等待下一次对账。
     *
     * @param now 本次查询结束时间
     */
    public void reconciliationPending(Instant now) {
        requireReconciling();
        this.status = AgentActionStatus.UNKNOWN;
        this.finishedAt = null;
        touch(now);
    }

    /**
     * 把尚未确认且已经超过截止时间的草案标记为过期。
     *
     * @param now 过期判断时间
     */
    public void expire(Instant now) {
        if (status == AgentActionStatus.AWAITING_CONFIRMATION) {
            this.status = AgentActionStatus.EXPIRED;
            this.finishedAt = now;
            touch(now);
        }
    }

    /**
     * 校验真实业务调用只允许从执行中状态结束。
     */
    private void requireExecuting() {
        if (status != AgentActionStatus.EXECUTING) {
            throw new IllegalStateException("操作草案不处于执行状态");
        }
    }

    /**
     * 校验只有已经被 MQ 消费者领取的操作能够提交对账结果。
     */
    private void requireReconciling() {
        if (status != AgentActionStatus.RECONCILING) {
            throw new IllegalStateException("操作草案不处于对账状态");
        }
    }
}
