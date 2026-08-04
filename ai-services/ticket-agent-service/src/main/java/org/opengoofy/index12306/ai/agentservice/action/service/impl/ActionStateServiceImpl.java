package org.opengoofy.index12306.ai.agentservice.action.service.impl;

import org.opengoofy.index12306.ai.agentservice.action.observability.AgentActionMetrics;
import org.opengoofy.index12306.ai.agentservice.action.security.ConfirmationTokenService;


import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionExecutionEntity;
import org.opengoofy.index12306.ai.agentservice.action.dto.ClaimedAction;
import org.opengoofy.index12306.ai.agentservice.action.config.AgentActionProperties;
import org.opengoofy.index12306.ai.agentservice.action.enums.ActionExecutionOutcome;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.opengoofy.index12306.ai.agentservice.action.dao.repository.ActionDraftRepository;
import org.opengoofy.index12306.ai.agentservice.action.dao.repository.ActionExecutionRepository;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionStateService;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionStateService.ExecutionLease;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.repository.TurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 在短数据库事务中维护操作草案和执行记录，外部 MCP 调用不占用数据库锁。
 */
@Service
public class ActionStateServiceImpl implements ActionStateService {

    private final ActionDraftRepository actionRepository;
    private final ActionExecutionRepository executionRepository;
    private final ConversationRepository conversationRepository;
    private final TurnRepository turnRepository;
    private final ConfirmationTokenService tokenService;
    private final AgentActionMetrics actionMetrics;
    private final ActionReconciliationService reconciliationService;
    private final AgentActionProperties actionProperties;
    private final Clock clock;

    /**
     * 创建高风险操作事务状态存储服务。
     *
     * @param actionRepository 操作草案仓储
     * @param executionRepository 执行记录仓储
     * @param conversationRepository 会话仓储
     * @param turnRepository 轮次仓储
     * @param tokenService 确认令牌校验服务
     * @param actionMetrics 高风险操作指标记录器
     * @param reconciliationService UNKNOWN 操作对账服务
     * @param actionProperties 高风险操作执行租约配置
     * @param clock 统一时钟
     */
    public ActionStateServiceImpl(
            ActionDraftRepository actionRepository,
            ActionExecutionRepository executionRepository,
            ConversationRepository conversationRepository,
            TurnRepository turnRepository,
            ConfirmationTokenService tokenService,
            AgentActionMetrics actionMetrics,
            ActionReconciliationService reconciliationService,
            AgentActionProperties actionProperties,
            Clock clock) {
        this.actionRepository = actionRepository;
        this.executionRepository = executionRepository;
        this.conversationRepository = conversationRepository;
        this.turnRepository = turnRepository;
        this.tokenService = tokenService;
        this.actionMetrics = actionMetrics;
        this.reconciliationService = reconciliationService;
        this.actionProperties = actionProperties;
        this.clock = clock;
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
    @Transactional
    @Override
    public ActionDraftEntity createPurchaseDraft(
            AgentRequestContext context,
            String payloadJson,
            String payloadHash,
            Instant expiresAt) {
        return createDraft(
                context, AgentActionType.TICKET_PURCHASE, payloadJson, payloadHash, expiresAt);
    }

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
    @Transactional
    @Override
    public ActionDraftEntity createDraft(
            AgentRequestContext context,
            AgentActionType actionType,
            String payloadJson,
            String payloadHash,
            Instant expiresAt) {
        // 再次校验会话和轮次归属，避免本地工具上下文被错误组合后写入跨用户草案。
        ConversationEntity conversation = Optional.ofNullable(conversationRepository.selectById(context.conversationId()))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(context.userId())) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        TurnEntity turn = Optional.ofNullable(turnRepository.selectById(context.turnId()))
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));
        if (!turn.getConversationId().equals(context.conversationId())
                || turn.getStatus() != TurnStatus.RUNNING) {
            throw new IllegalStateException("只有当前运行中轮次可以创建操作草案");
        }

        // 一个回答轮次只能产生一个待确认操作，模型重试相同参数时复用原草案。
        List<ActionDraftEntity> existingActions = actionRepository.findAllByTurnId(context.turnId());
        if (!existingActions.isEmpty()) {
            ActionDraftEntity existing = existingActions.get(0);
            if (existing.getActionType() != actionType || !existing.getPayloadHash().equals(payloadHash)) {
                throw new IllegalStateException("当前轮次已经生成了不同的操作草案");
            }
            return existing;
        }
        ActionDraftEntity created = ActionDraftEntity.create(
                context.userId(), context.conversationId(), context.turnId(),
                actionType, payloadJson, payloadHash, expiresAt, clock.instant());
        actionRepository.insert(created);
        return created;
    }

    /**
     * 查询轮次内的操作草案，并在需要时持久化过期状态。
     *
     * @param userId 当前用户标识
     * @param turnId 轮次标识
     * @return 当前轮次草案
     */
    @Transactional
    @Override
    public Optional<ActionDraftEntity> findByTurn(String userId, String turnId) {
        List<ActionDraftEntity> actions = actionRepository.findAllByTurnId(turnId);
        ActionDraftEntity action = actions.isEmpty() ? null : actions.get(0);
        if (action == null) {
            return Optional.empty();
        }
        assertOwner(action, userId);

        // 待确认草案超过截止时间后立即转为 EXPIRED，旧令牌随后无法再消费。
        expireIfNecessary(action, clock.instant());
        return Optional.of(action);
    }

    /**
     * 查询当前用户会话最近的高风险操作，并在需要时固化过期状态。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 会话最近操作
     */
    @Transactional
    @Override
    public Optional<ActionDraftEntity> findLatestByConversation(
            String userId,
            String conversationId) {
        ConversationEntity conversation = Optional.ofNullable(conversationRepository.selectById(conversationId))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }

        // 即使会话暂时没有操作，也必须先完成会话归属校验，禁止通过标识探测。
        ActionDraftEntity action = actionRepository
                .findFirstByConversationIdOrderByCreatedAtDesc(conversationId)
                .orElse(null);
        if (action == null) {
            return Optional.empty();
        }
        assertOwner(action, userId);
        expireIfNecessary(action, clock.instant());
        return Optional.of(action);
    }

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
    @Transactional(noRollbackFor = ActionConfirmationExpiredException.class)
    @Override
    public ClaimedAction claim(
            String userId,
            String actionId,
            String confirmationToken,
            String requestId,
            String idempotencyKey) {
        ActionDraftEntity action = actionRepository.findLockedById(actionId)
                .orElseThrow(() -> new IllegalArgumentException("操作草案不存在"));
        assertOwner(action, userId);
        Instant now = clock.instant();

        // 令牌校验发生在草案行锁内，防止并发请求同时通过状态检查。
        if (action.getStatus() != AgentActionStatus.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("操作草案已经被确认或终止");
        }
        if (!now.isBefore(action.getConfirmationExpiresAt())) {
            // 过期状态必须随当前事务提交，不能因为向上抛出冲突异常而回滚为待确认。
            expireIfNecessary(action, now);
            throw new ActionConfirmationExpiredException();
        }
        if (!tokenService.matches(action, confirmationToken)) {
            throw new SecurityException("操作确认令牌无效");
        }
        ActionExecutionEntity idempotentExecution = executionRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElse(null);
        if (idempotentExecution != null) {
            throw new IllegalStateException("确认幂等键已经被使用");
        }

        // 确认事务只创建 QUEUED 记录；事务提交后还必须再次领取有期限的真实写执行权。
        ActionExecutionEntity execution = ActionExecutionEntity.queue(
                action.getId(), requestId, idempotencyKey, now);
        executionRepository.insert(execution);
        action.queueExecution(execution.getId(), now);
        // 确认消费后显式保存排队状态，保证令牌消费与执行记录在同一事务内提交。
        actionRepository.updateById(action);
        return new ClaimedAction(
                action.getId(), execution.getId(), requestId, action.getActionType(),
                action.getUserId(), action.getConversationId(),
                action.getTurnId(), action.getPayloadJson(), action.getPayloadHash());
    }

    /**
     * 锁定草案和执行记录，为当前实例签发新的 fencing token。
     *
     * @param actionId 草案标识
     * @param executionId 执行记录标识
     * @param owner 执行实例标识
     * @return 本次数据库租约
     */
    @Transactional
    @Override
    public ExecutionLease startExecution(String actionId, String executionId, String owner) {
        ActionDraftEntity action = actionRepository.findLockedById(actionId)
                .orElseThrow(() -> new IllegalStateException("操作草案不存在"));
        ActionExecutionEntity execution = executionRepository.findLockedById(executionId)
                .orElseThrow(() -> new IllegalStateException("操作执行记录不存在"));
        if (!execution.getActionId().equals(actionId)
                || !executionId.equals(action.getExecutionId())) {
            throw new IllegalStateException("操作草案与执行记录不匹配");
        }

        // 同一事务同步迁移草案和执行审计，只有拿到租约的实例才可以调用下游写接口。
        Instant now = clock.instant();
        long fencingToken = execution.claim(
                owner, now.plus(actionProperties.executionLease()), now);
        action.beginExecution(now);
        executionRepository.updateById(execution);
        actionRepository.updateById(action);
        return new ExecutionLease(actionId, executionId, owner, fencingToken);
    }

    /**
     * 使用执行记录行锁校验 fencing token 并延长租约。
     *
     * @param lease 当前执行租约
     * @return 成功续租返回 true，旧执行者或终态记录返回 false
     */
    @Transactional
    @Override
    public boolean heartbeat(ExecutionLease lease) {
        ActionExecutionEntity execution = executionRepository.findLockedById(lease.executionId())
                .orElse(null);
        if (execution == null
                || execution.getOutcome() != ActionExecutionOutcome.STARTED
                || !lease.owner().equals(execution.getLeaseOwner())
                || lease.fencingToken() != execution.getFencingToken()) {
            return false;
        }

        // 只有数据库中仍生效的执行者可以续租，避免旧实例延长新实例的执行窗口。
        Instant now = clock.instant();
        execution.heartbeat(
                lease.owner(), lease.fencingToken(),
                now.plus(actionProperties.executionLease()), now);
        executionRepository.updateById(execution);
        return true;
    }

    /**
     * 原子保存脱敏业务结果并结束执行记录。
     *
     * @param lease 当前执行租约
     * @param safeResultJson 脱敏结果 JSON
     * @param resultReference 订单号
     * @param responseFingerprint 响应指纹
     */
    @Transactional
    @Override
    public void succeed(
            ExecutionLease lease,
            String safeResultJson,
            String resultReference,
            String responseFingerprint) {
        ActionDraftEntity action = actionRepository.findLockedById(lease.actionId())
                .orElseThrow(() -> new IllegalStateException("操作草案不存在"));
        ActionExecutionEntity execution = requireLeasedExecution(action, lease);
        Instant now = clock.instant();

        // 草案状态和独立执行审计必须在同一事务中完成，避免一边成功一边仍为运行中。
        action.succeed(safeResultJson, resultReference, now);
        execution.succeed(
                lease.owner(), lease.fencingToken(), resultReference, responseFingerprint, now);
        actionRepository.updateById(action);
        executionRepository.updateById(execution);
    }

    /**
     * 将下游明确拒绝且确认未成功的写调用记录为 FAILED，允许用户修正参数后创建新草案。
     *
     * @param lease 当前执行租约
     * @param category 稳定失败分类
     * @param exceptionType 异常类型
     */
    @Transactional
    @Override
    public void fail(ExecutionLease lease, String category, String exceptionType) {
        ActionDraftEntity action = actionRepository.findLockedById(lease.actionId())
                .orElseThrow(() -> new IllegalStateException("操作草案不存在"));
        ActionExecutionEntity execution = requireLeasedExecution(action, lease);
        Instant now = clock.instant();

        // 明确业务拒绝同时结束草案和执行审计，避免前端误显示为结果待核对。
        action.fail(category, now);
        execution.fail(lease.owner(), lease.fencingToken(), category, exceptionType, now);
        actionRepository.updateById(action);
        executionRepository.updateById(execution);
    }

    /**
     * 将下游结果不确定的真实写调用标记为 UNKNOWN，禁止自动重试。
     *
     * @param lease 当前执行租约
     * @param category 稳定失败分类
     * @param exceptionType 异常类型
     */
    @Transactional
    @Override
    public void markUnknown(ExecutionLease lease, String category, String exceptionType) {
        ActionDraftEntity action = actionRepository.findLockedById(lease.actionId())
                .orElseThrow(() -> new IllegalStateException("操作草案不存在"));
        ActionExecutionEntity execution = requireLeasedExecution(action, lease);
        Instant now = clock.instant();

        // 超时或网络异常可能发生在下游已经创建订单之后，只能等待订单查询核对。
        action.markUnknown(category, now);
        execution.markUnknown(lease.owner(), lease.fencingToken(), category, exceptionType, now);
        actionRepository.updateById(action);
        executionRepository.updateById(execution);
        // 与 UNKNOWN 状态同事务创建 Outbox 事件，避免状态已提交但恢复任务丢失。
        reconciliationService.request(lease.actionId());
    }

    /**
     * 扫描排队超时和租约过期执行，分别结束失败或转入只读对账流程。
     *
     * @return 成功迁移为 UNKNOWN 的执行数量
     */
    @Transactional
    @Override
    public int recoverExpiredExecutions() {
        Instant now = clock.instant();
        int recovered = 0;
        Instant queueDeadline = now.minus(actionProperties.executionLease());
        for (ActionExecutionEntity candidate : executionRepository.findAbandonedQueued(queueDeadline)) {
            // 与正常领取保持草案、执行记录的固定加锁顺序，避免恢复器和确认线程形成死锁。
            ActionDraftEntity action = actionRepository.findLockedById(candidate.getActionId())
                    .orElseThrow(() -> new IllegalStateException("操作草案不存在"));
            ActionExecutionEntity execution = executionRepository.findLockedById(candidate.getId()).orElse(null);
            if (execution == null || execution.getOutcome() != ActionExecutionOutcome.QUEUED) {
                continue;
            }
            // QUEUED 从未取得下游写权限，因此可以结束为明确失败而不创建对账任务。
            action.failQueued("ACTION_EXECUTION_NOT_STARTED", now);
            execution.abandonQueued("ACTION_EXECUTION_NOT_STARTED", now);
            actionRepository.updateById(action);
            executionRepository.updateById(execution);
            recovered++;
        }
        for (ActionExecutionEntity candidate : executionRepository.findExpiredStarted(now)) {
            // 候选快照可能已被处理；仍按草案、执行记录顺序加锁后再次校验租约。
            ActionDraftEntity action = actionRepository.findLockedById(candidate.getActionId())
                    .orElseThrow(() -> new IllegalStateException("操作草案不存在"));
            ActionExecutionEntity execution = executionRepository.findLockedById(candidate.getId()).orElse(null);
            if (execution == null
                    || execution.getOutcome() != ActionExecutionOutcome.STARTED
                    || execution.getLeaseUntil() == null
                    || now.isBefore(execution.getLeaseUntil())) {
                continue;
            }

            // 宕机后无法证明下游没有成功，禁止自动重放，只创建同事务对账任务。
            action.markUnknown("ACTION_EXECUTION_LEASE_EXPIRED", now);
            execution.recoverExpired("ACTION_EXECUTION_LEASE_EXPIRED", now);
            actionRepository.updateById(action);
            executionRepository.updateById(execution);
            reconciliationService.request(action.getId());
            recovered++;
        }
        return recovered;
    }

    /**
     * 读取并校验当前用户的操作草案。
     *
     * @param userId 当前用户标识
     * @param actionId 草案标识
     * @return 操作草案
     */
    @Transactional
    @Override
    public ActionDraftEntity get(String userId, String actionId) {
        ActionDraftEntity action = Optional.ofNullable(actionRepository.selectById(actionId))
                .orElseThrow(() -> new IllegalArgumentException("操作草案不存在"));
        assertOwner(action, userId);

        // 状态查询也要及时固化过期结果，避免客户端持续看到已经无法确认的草案。
        expireIfNecessary(action, clock.instant());
        return action;
    }

    /**
     * 在待确认草案首次超过截止时间时固化终态并记录一次过期指标。
     *
     * @param action 操作草案
     * @param now 当前时间
     */
    private void expireIfNecessary(
            ActionDraftEntity action,
            Instant now) {
        if (action.getStatus() != AgentActionStatus.AWAITING_CONFIRMATION
                || now.isBefore(action.getConfirmationExpiresAt())) {
            return;
        }

        // 状态变化和指标只发生一次，后续状态查询不会重复累计同一草案。
        action.expire(now);
        // 过期状态需要在当前事务内显式写回，避免后续读取仍看到待确认草稿。
        actionRepository.updateById(action);
        actionMetrics.recordConfirmationExpired(action.getActionType());
    }

    /**
     * 读取草案关联的唯一执行记录。
     *
     * @param action 草案实体
     * @return 执行记录
     */
    private ActionExecutionEntity requireExecution(ActionDraftEntity action) {
        if (action.getExecutionId() == null) {
            throw new IllegalStateException("操作草案缺少执行记录");
        }
        return Optional.ofNullable(executionRepository.selectById(action.getExecutionId()))
                .orElseThrow(() -> new IllegalStateException("操作执行记录不存在"));
    }

    /**
     * 读取执行记录并校验它与本次租约属于同一草案和同一执行标识。
     *
     * @param action 已锁定草案
     * @param lease 当前执行租约
     * @return 与租约绑定的执行记录
     */
    private ActionExecutionEntity requireLeasedExecution(
            ActionDraftEntity action,
            ExecutionLease lease) {
        ActionExecutionEntity execution = requireExecution(action);
        if (!execution.getId().equals(lease.executionId())) {
            throw new IllegalStateException("操作执行记录与租约不匹配");
        }
        return execution;
    }

    /**
     * 校验操作草案属于当前用户。
     *
     * @param action 操作草案
     * @param userId 当前用户标识
     */
    private void assertOwner(ActionDraftEntity action, String userId) {
        if (userId == null || !userId.equals(action.getUserId())) {
            throw new IllegalArgumentException("无权访问该操作草案");
        }
    }

    /**
     * 标识已经在当前事务内固化为过期、但仍需向确认接口返回冲突的状态。
     */
    private static final class ActionConfirmationExpiredException extends IllegalStateException {
    }

}
