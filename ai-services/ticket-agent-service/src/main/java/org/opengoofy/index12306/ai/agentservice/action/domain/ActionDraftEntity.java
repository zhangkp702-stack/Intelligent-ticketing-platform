package org.opengoofy.index12306.ai.agentservice.action.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.ai.agentservice.memory.domain.AgentBaseEntity;

import java.time.Instant;
import java.util.Objects;

/**
 * 模型只能创建、用户确认后才能进入执行态的高风险操作草案。
 */
@Getter
@Entity
@Table(name = "t_agent_action_draft")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionDraftEntity extends AgentBaseEntity {

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "conversation_id", nullable = false, length = 32)
    private String conversationId;

    @Column(name = "turn_id", nullable = false, length = 32)
    private String turnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32)
    private AgentActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AgentActionStatus status;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "confirmation_expires_at", nullable = false)
    private Instant confirmationExpiresAt;

    @Column(name = "confirmation_consumed_at")
    private Instant confirmationConsumedAt;

    @Column(name = "execution_id", length = 32)
    private String executionId;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "result_reference", length = 128)
    private String resultReference;

    @Column(name = "failure_category", length = 64)
    private String failureCategory;

    @Column(name = "finished_at")
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
     * 原子消费确认机会并进入执行状态。
     *
     * @param confirmedExecutionId 执行记录标识
     * @param now 用户确认时间
     */
    public void startExecution(String confirmedExecutionId, Instant now) {
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
        this.status = AgentActionStatus.EXECUTING;
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
        this.finishedAt = now;
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
}
