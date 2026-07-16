package org.opengoofy.index12306.ai.agentservice.action;

import org.opengoofy.index12306.ai.agentservice.action.domain.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.domain.ActionExecutionEntity;
import org.opengoofy.index12306.ai.agentservice.action.domain.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.action.domain.AgentActionType;
import org.opengoofy.index12306.ai.agentservice.action.repository.ActionDraftRepository;
import org.opengoofy.index12306.ai.agentservice.action.repository.ActionExecutionRepository;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ConversationRepository;
import org.opengoofy.index12306.ai.agentservice.memory.repository.TurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 在短数据库事务中维护操作草案和执行记录，外部 MCP 调用不占用数据库锁。
 */
@Service
public class ActionStateStore {

    private final ActionDraftRepository actionRepository;
    private final ActionExecutionRepository executionRepository;
    private final ConversationRepository conversationRepository;
    private final TurnRepository turnRepository;
    private final ConfirmationTokenService tokenService;
    private final Clock clock;

    /**
     * 创建高风险操作事务状态存储服务。
     *
     * @param actionRepository 操作草案仓储
     * @param executionRepository 执行记录仓储
     * @param conversationRepository 会话仓储
     * @param turnRepository 轮次仓储
     * @param tokenService 确认令牌校验服务
     * @param clock 统一时钟
     */
    public ActionStateStore(
            ActionDraftRepository actionRepository,
            ActionExecutionRepository executionRepository,
            ConversationRepository conversationRepository,
            TurnRepository turnRepository,
            ConfirmationTokenService tokenService,
            Clock clock) {
        this.actionRepository = actionRepository;
        this.executionRepository = executionRepository;
        this.conversationRepository = conversationRepository;
        this.turnRepository = turnRepository;
        this.tokenService = tokenService;
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
    public ActionDraftEntity createPurchaseDraft(
            AgentRequestContext context,
            String payloadJson,
            String payloadHash,
            Instant expiresAt) {
        // 再次校验会话和轮次归属，避免本地工具上下文被错误组合后写入跨用户草案。
        ConversationEntity conversation = conversationRepository.findById(context.conversationId())
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conversation.belongsTo(context.userId())) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        TurnEntity turn = turnRepository.findById(context.turnId())
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在"));
        if (!turn.getConversationId().equals(context.conversationId())
                || !context.topicId().equals(turn.getTopicId())
                || turn.getStatus() != TurnStatus.RUNNING) {
            throw new IllegalStateException("只有当前运行中轮次可以创建操作草案");
        }

        // 模型重试同一工具时复用原草案，但拒绝在同一轮次偷偷替换已经展示的购票参数。
        ActionDraftEntity existing = actionRepository
                .findByTurnIdAndActionType(context.turnId(), AgentActionType.TICKET_PURCHASE)
                .orElse(null);
        if (existing != null) {
            if (!existing.getPayloadHash().equals(payloadHash)) {
                throw new IllegalStateException("当前轮次已经生成了不同参数的购票草案");
            }
            return existing;
        }
        ActionDraftEntity created = ActionDraftEntity.createPurchase(
                context.userId(), context.conversationId(), context.topicId(), context.turnId(),
                payloadJson, payloadHash, expiresAt, clock.instant());
        return actionRepository.save(created);
    }

    /**
     * 查询轮次内的操作草案，并在需要时持久化过期状态。
     *
     * @param userId 当前用户标识
     * @param turnId 轮次标识
     * @return 当前轮次草案
     */
    @Transactional
    public Optional<ActionDraftEntity> findByTurn(String userId, String turnId) {
        ActionDraftEntity action = actionRepository
                .findByTurnIdAndActionType(turnId, AgentActionType.TICKET_PURCHASE)
                .orElse(null);
        if (action == null) {
            return Optional.empty();
        }
        assertOwner(action, userId);

        // 待确认草案超过截止时间后立即转为 EXPIRED，旧令牌随后无法再消费。
        if (action.getStatus() == AgentActionStatus.AWAITING_CONFIRMATION
                && !clock.instant().isBefore(action.getConfirmationExpiresAt())) {
            action.expire(clock.instant());
        }
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
    @Transactional
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
            action.expire(now);
            throw new IllegalStateException("操作确认已经过期");
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

        // 先创建执行记录标识，再把草案和执行记录在同一事务中关联为 EXECUTING。
        ActionExecutionEntity execution = ActionExecutionEntity.start(
                action.getId(), requestId, idempotencyKey, now);
        executionRepository.save(execution);
        action.startExecution(execution.getId(), now);
        return new ClaimedAction(
                action.getId(), execution.getId(), requestId, action.getUserId(), action.getConversationId(),
                action.getTopicId(), action.getTurnId(), action.getPayloadJson(), action.getPayloadHash());
    }

    /**
     * 原子保存脱敏业务结果并结束执行记录。
     *
     * @param actionId 草案标识
     * @param safeResultJson 脱敏结果 JSON
     * @param resultReference 订单号
     * @param responseFingerprint 响应指纹
     */
    @Transactional
    public void succeed(
            String actionId,
            String safeResultJson,
            String resultReference,
            String responseFingerprint) {
        ActionDraftEntity action = actionRepository.findLockedById(actionId)
                .orElseThrow(() -> new IllegalStateException("操作草案不存在"));
        ActionExecutionEntity execution = requireExecution(action);
        Instant now = clock.instant();

        // 草案状态和独立执行审计必须在同一事务中完成，避免一边成功一边仍为运行中。
        action.succeed(safeResultJson, resultReference, now);
        execution.succeed(resultReference, responseFingerprint, now);
    }

    /**
     * 将下游结果不确定的真实写调用标记为 UNKNOWN，禁止自动重试。
     *
     * @param actionId 草案标识
     * @param category 稳定失败分类
     * @param exceptionType 异常类型
     */
    @Transactional
    public void markUnknown(String actionId, String category, String exceptionType) {
        ActionDraftEntity action = actionRepository.findLockedById(actionId)
                .orElseThrow(() -> new IllegalStateException("操作草案不存在"));
        ActionExecutionEntity execution = requireExecution(action);
        Instant now = clock.instant();

        // 超时或网络异常可能发生在下游已经创建订单之后，只能等待订单查询核对。
        action.markUnknown(category, now);
        execution.markUnknown(category, exceptionType, now);
    }

    /**
     * 读取并校验当前用户的操作草案。
     *
     * @param userId 当前用户标识
     * @param actionId 草案标识
     * @return 操作草案
     */
    @Transactional
    public ActionDraftEntity get(String userId, String actionId) {
        ActionDraftEntity action = actionRepository.findById(actionId)
                .orElseThrow(() -> new IllegalArgumentException("操作草案不存在"));
        assertOwner(action, userId);

        // 状态查询也要及时固化过期结果，避免客户端持续看到已经无法确认的草案。
        if (action.getStatus() == AgentActionStatus.AWAITING_CONFIRMATION
                && !clock.instant().isBefore(action.getConfirmationExpiresAt())) {
            action.expire(clock.instant());
        }
        return action;
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
        return executionRepository.findById(action.getExecutionId())
                .orElseThrow(() -> new IllegalStateException("操作执行记录不存在"));
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
     * @param actionId 草案标识
     * @param executionId 执行记录标识
     * @param requestId 确认请求标识
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param topicId 主题标识
     * @param turnId 轮次标识
     * @param payloadJson 规范化参数 JSON
     * @param payloadHash 参数指纹
     */
    public record ClaimedAction(
            String actionId,
            String executionId,
            String requestId,
            String userId,
            String conversationId,
            String topicId,
            String turnId,
            String payloadJson,
            String payloadHash) {
    }
}
