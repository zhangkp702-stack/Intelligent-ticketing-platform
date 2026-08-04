package org.opengoofy.index12306.ai.agentservice.action.service;

import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.dto.ClaimedAction;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandLease;

import java.time.Instant;
import java.util.Optional;

/**
 * 定义高风险操作草案和执行记录的事务状态边界。
 */
public interface ActionStateService {

    /** Agent 高风险动作共享的可靠命令命名空间。 */
    String COMMAND_NAMESPACE = "agent-action-execution";

    /**
     * 构造动作执行使用的稳定可靠命令键。
     *
     * @param actionId 服务端动作标识
     * @return 以 actionId 同时作为业务命令和本库路由的命令键
     */
    static ReliableCommandKey commandKey(String actionId) {
        return new ReliableCommandKey(COMMAND_NAMESPACE, actionId, actionId);
    }

    /**
     * 在当前运行中轮次内幂等创建购票草案。
     *
     * @param context 已验证的对话请求上下文
     * @param payloadJson 规范参数 JSON
     * @param payloadHash 参数指纹
     * @param expiresAt 确认截止时间
     * @return 新建或同参数既有草案
     */
    ActionDraftEntity createPurchaseDraft(
            AgentRequestContext context,
            String payloadJson,
            String payloadHash,
            Instant expiresAt);

    /**
     * 在当前运行中轮次内幂等创建指定类型的高风险操作草案。
     *
     * @param context 已验证的对话请求上下文
     * @param actionType 操作类型
     * @param payloadJson 规范参数 JSON
     * @param payloadHash 参数指纹
     * @param expiresAt 确认截止时间
     * @return 新建或同参数已有草案
     */
    ActionDraftEntity createDraft(
            AgentRequestContext context,
            AgentActionType actionType,
            String payloadJson,
            String payloadHash,
            Instant expiresAt);

    /**
     * 查询轮次内的操作草案，并在需要时持久化过期状态。
     *
     * @param userId 当前用户标识
     * @param turnId 轮次标识
     * @return 当前轮次草案
     */
    Optional<ActionDraftEntity> findByTurn(String userId, String turnId);

    /**
     * 查询当前用户会话最近的高风险操作。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 会话最近操作
     */
    Optional<ActionDraftEntity> findLatestByConversation(String userId, String conversationId);

    /**
     * 锁定草案、校验令牌并创建唯一执行记录。
     *
     * @param userId 当前用户标识
     * @param actionId 草案标识
     * @param confirmationToken 确认令牌
     * @param requestId 确认请求标识
     * @param idempotencyKey 确认幂等键
     * @return 已领取执行权的不可变快照
     */
    ClaimedAction claim(
            String userId,
            String actionId,
            String confirmationToken,
            String requestId,
            String idempotencyKey);

    /**
     * 为已入队执行记录领取有期限的真实写执行权。
     *
     * @param actionId 草案标识
     * @param executionId 执行记录标识
     * @return 本次数据库租约及 fencing token
     */
    ExecutionLease startExecution(String actionId, String executionId);

    /**
     * 在 fencing token 仍有效时延长真实写执行租约。
     *
     * @param lease 当前执行租约
     * @return 成功续租返回 true，执行权已经失效返回 false
     */
    boolean heartbeat(ExecutionLease lease);

    /**
     * 原子保存脱敏业务结果并结束执行记录。
     *
     * @param lease 当前执行租约
     * @param safeResultJson 脱敏结果 JSON
     * @param resultReference 业务结果引用
     */
    void succeed(
            ExecutionLease lease,
            String safeResultJson,
            String resultReference);

    /**
     * 将明确失败的写调用记录为失败终态。
     *
     * @param lease 当前执行租约
     * @param category 稳定失败分类
     * @param exceptionType 异常类型
     */
    void fail(ExecutionLease lease, String category, String exceptionType);

    /**
     * 将结果不确定的真实写调用标记为待人工核对。
     *
     * @param lease 当前执行租约
     * @param category 稳定失败分类
     * @param exceptionType 异常类型
     */
    void markUnknown(ExecutionLease lease, String category, String exceptionType);

    /**
     * 恢复未领取的 QUEUED 和租约过期但没有终态的 STARTED 执行。
     *
     * @return 本次成功恢复的执行数量
     */
    int recoverExpiredExecutions();

    /**
     * 读取并校验当前用户的操作草案。
     *
     * @param userId 当前用户标识
     * @param actionId 草案标识
     * @return 操作草案
     */
    ActionDraftEntity get(String userId, String actionId);

    /**
     * 一次数据库执行权领取结果。
     *
     * @param actionId 草案标识
     * @param executionId 执行记录标识
     * @param commandKey 通用可靠命令键
     * @param commandLease 通用可靠命令围栏租约
     */
    record ExecutionLease(
            String actionId,
            String executionId,
            ReliableCommandKey commandKey,
            ReliableCommandLease commandLease) {
    }
}
