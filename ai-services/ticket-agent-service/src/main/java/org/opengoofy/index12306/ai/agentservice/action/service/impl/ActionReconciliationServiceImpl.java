package org.opengoofy.index12306.ai.agentservice.action.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opengoofy.index12306.ai.agentservice.action.config.ActionReconciliationProperties;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionExecutionEntity;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionReconciliationEntity;
import org.opengoofy.index12306.ai.agentservice.action.dao.repository.ActionDraftRepository;
import org.opengoofy.index12306.ai.agentservice.action.dao.repository.ActionExecutionRepository;
import org.opengoofy.index12306.ai.agentservice.action.dao.repository.ActionReconciliationRepository;
import org.opengoofy.index12306.ai.agentservice.action.enums.ActionReconciliationStatus;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 以数据库事务协调 UNKNOWN 操作的 Outbox 发布、Inbox 领取和权威结果回写。
 */
@Service
public class ActionReconciliationServiceImpl implements ActionReconciliationService {

    private static final String CONSUMER_NAME = "agent-action-reconciliation-v1";

    private final ActionReconciliationProperties properties;
    private final ActionReconciliationRepository reconciliationRepository;
    private final ActionDraftRepository actionRepository;
    private final ActionExecutionRepository executionRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 创建动作对账事务服务。
     *
     * @param properties 对账重试配置
     * @param reconciliationRepository 对账事件仓储
     * @param actionRepository 操作草案仓储
     * @param executionRepository 执行审计仓储
     * @param objectMapper JSON 解析器
     * @param clock 统一时钟
     */
    public ActionReconciliationServiceImpl(
            ActionReconciliationProperties properties,
            ActionReconciliationRepository reconciliationRepository,
            ActionDraftRepository actionRepository,
            ActionExecutionRepository executionRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.reconciliationRepository = reconciliationRepository;
        this.actionRepository = actionRepository;
        this.executionRepository = executionRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在 UNKNOWN 状态提交事务内创建唯一对账 Outbox 事件。
     *
     * @param actionId 操作草案标识
     */
    @Transactional
    @Override
    public void request(String actionId) {
        // 唯一 actionId 已存在时直接复用，避免网络异常处理本身产生重复事件。
        if (reconciliationRepository.findByActionId(actionId).isPresent()) {
            return;
        }
        reconciliationRepository.insert(ActionReconciliationEntity.pending(
                actionId, properties.maxAttempts(), clock.instant()));
    }

    /**
     * 查询本轮可发布的数据库 Outbox 事件。
     *
     * @return 最多一百条待发布事件
     */
    @Override
    public List<PendingEvent> pendingEvents() {
        // 发布器只读取 PENDING，不在发送 MQ 期间持有数据库事务和行锁。
        return reconciliationRepository.findTop100ByStatusOrderByUpdatedAtAsc(ActionReconciliationStatus.PENDING)
                .stream()
                .map(entity -> new PendingEvent(entity.getId(), entity.getActionId(), entity.getEventVersion()))
                .toList();
    }

    /**
     * 在 MQ 确认接收后持久化消息标识。
     *
     * @param eventId 事件标识
     * @param eventVersion 事件版本
     * @param messageId MQ 消息标识
     */
    @Transactional
    @Override
    public void markPublished(String eventId, long eventVersion, String messageId) {
        ActionReconciliationEntity event = requireEvent(eventId);
        // 迟到的发布确认不能覆盖已经恢复或消费的新状态。
        if (event.getEventVersion() != eventVersion) {
            return;
        }
        event.published(messageId, clock.instant());
        reconciliationRepository.updateById(event);
    }

    /**
     * 将消费租约或退避时间到期的事件恢复为可发布状态。
     *
     * @return 实际恢复数量
     */
    @Transactional
    @Override
    public int recoverExpired() {
        Instant now = clock.instant();
        List<ActionReconciliationEntity> candidates = reconciliationRepository.findExpiredRunning(
                ActionReconciliationStatus.RUNNING, now);
        candidates.addAll(reconciliationRepository.findDueRetries(ActionReconciliationStatus.RETRY_WAIT, now));
        int recovered = 0;
        // 查询已在同一事务内加锁，逐条状态迁移不会被其他实例重复恢复。
        for (ActionReconciliationEntity event : candidates) {
            boolean abandonedRunningAttempt = event.getStatus() == ActionReconciliationStatus.RUNNING;
            if (event.recoverForRepublish(now)) {
                if (abandonedRunningAttempt) {
                    // 消费者可能在查询或提交结果前宕机，租约恢复时三份状态必须一起退回可重试状态。
                    ActionDraftEntity action = requireAction(event.getActionId());
                    ActionExecutionEntity execution = requireExecution(action);
                    action.reconciliationPending(now);
                    execution.reconciliationPending(now);
                    actionRepository.updateById(action);
                    executionRepository.updateById(execution);
                }
                reconciliationRepository.updateById(event);
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * 以数据库行锁幂等领取消息，并把操作和执行审计一起转入 RECONCILING。
     *
     * @param eventId 事件标识
     * @param eventVersion 事件版本
     * @param workerId 消费实例标识
     * @return 成功领取时的不可变工作项
     */
    @Transactional
    @Override
    public Optional<WorkItem> claim(String eventId, long eventVersion, String workerId) {
        ActionReconciliationEntity event = requireEvent(eventId);
        Instant now = clock.instant();
        if (!event.claim(eventVersion, workerId, CONSUMER_NAME, now, properties.leaseDuration())) {
            reconciliationRepository.updateById(event);
            return Optional.empty();
        }

        // 对账事件、草案和执行记录在同一事务进入运行态，崩溃后由租约恢复。
        ActionDraftEntity action = requireAction(event.getActionId());
        ActionExecutionEntity execution = requireExecution(action);
        action.beginReconciliation(now);
        execution.beginReconciliation(now);
        reconciliationRepository.updateById(event);
        actionRepository.updateById(action);
        executionRepository.updateById(execution);
        return Optional.of(new WorkItem(
                event.getId(), event.getEventVersion(), action.getId(), action.getActionType(),
                action.getUserId(), action.getConversationId(), action.getTurnId(),
                action.getPayloadJson(), action.getPayloadHash()));
    }

    /**
     * 校验下游权威状态并原子收口草案、执行审计和对账事件。
     *
     * @param eventId 事件标识
     * @param result 下游操作状态
     * @return 是否已经得到成功或失败终态
     */
    @Transactional
    @Override
    public boolean complete(String eventId, DownstreamResult result) {
        ActionReconciliationEntity event = requireEvent(eventId);
        ActionDraftEntity action = requireAction(event.getActionId());
        ActionExecutionEntity execution = requireExecution(action);
        Instant now = clock.instant();
        assertSameOperation(action, result);

        if (result.status() == DownstreamStatus.PROCESSING) {
            // 下游仍在处理中不代表失败，恢复 UNKNOWN 并通过相同 Outbox 事件延迟查询。
            action.reconciliationPending(now);
            execution.reconciliationPending(now);
            event.retry("DOWNSTREAM_PROCESSING", "下游操作仍在处理中", now, properties.retryDelay());
            updateAll(event, action, execution);
            return false;
        }
        if (result.status() == DownstreamStatus.FAILED) {
            // 只有下游操作事实表明确失败，Agent 才允许结束为 FAILED。
            action.reconcileFailed("DOWNSTREAM_CONFIRMED_FAILED", now);
            execution.reconcileFailed("DOWNSTREAM_CONFIRMED_FAILED", now);
            event.succeed(now);
            updateAll(event, action, execution);
            return true;
        }

        // 成功结果必须重新按动作类型白名单化，不能直接保存任意下游 JSON。
        NormalizedResult normalized = normalizeSuccess(action, result.safeResultJson());
        String fingerprint = sha256(normalized.safeResultJson());
        action.reconcileSucceeded(normalized.safeResultJson(), normalized.reference(), now);
        execution.reconcileSucceeded(normalized.reference(), fingerprint, now);
        event.succeed(now);
        updateAll(event, action, execution);
        return true;
    }

    /**
     * 记录临时查询异常，并按持久化次数决定是否继续重试。
     *
     * @param eventId 事件标识
     * @param category 稳定失败分类
     * @param safeMessage 安全错误说明
     * @return 是否仍允许重试
     */
    @Transactional
    @Override
    public boolean fail(String eventId, String category, String safeMessage) {
        ActionReconciliationEntity event = requireEvent(eventId);
        ActionDraftEntity action = requireAction(event.getActionId());
        ActionExecutionEntity execution = requireExecution(action);
        Instant now = clock.instant();

        // 查询失败不得重放真实写请求，草案恢复 UNKNOWN 供下次只读对账。
        action.reconciliationPending(now);
        execution.reconciliationPending(now);
        boolean retry = event.retry(category, safeMessage, now, properties.retryDelay());
        updateAll(event, action, execution);
        return retry;
    }

    /**
     * 校验对账响应与原草案属于同一操作和同一业务类型。
     *
     * @param action 原始草案
     * @param result 下游响应
     */
    private void assertSameOperation(ActionDraftEntity action, DownstreamResult result) {
        if (!action.getId().equals(result.actionId())
                || !expectedOperationType(action.getActionType()).equals(result.operationType())) {
            throw new IllegalStateException("下游对账结果与原操作不一致");
        }
    }

    /**
     * 返回 Agent 动作对应的 ticket-service 稳定操作类型。
     *
     * @param actionType Agent 动作类型
     * @return 下游操作类型
     */
    private String expectedOperationType(AgentActionType actionType) {
        return switch (actionType) {
            case TICKET_PURCHASE -> "PURCHASE_TICKET";
            case TICKET_CANCEL -> "CANCEL_TICKET_ORDER";
            case TICKET_REFUND -> "REFUND_TICKET";
        };
    }

    /**
     * 按动作类型验证并重建最小成功结果。
     *
     * @param action 原始草案
     * @param safeResultJson 下游白名单结果
     * @return 可写入 Agent 状态的结果和业务引用
     */
    private NormalizedResult normalizeSuccess(ActionDraftEntity action, String safeResultJson) {
        try {
            JsonNode result = objectMapper.readTree(Objects.requireNonNull(safeResultJson, "成功结果不能为空"));
            String orderSn;
            if (action.getActionType() == AgentActionType.TICKET_CANCEL) {
                // 取消接口不回传订单号，使用确认前已经持久化且校验过的不可变草案补齐。
                orderSn = requiredText(objectMapper.readTree(action.getPayloadJson()), "orderSn");
                ObjectNode normalized = objectMapper.createObjectNode();
                normalized.put("orderSn", orderSn);
                normalized.put("cancelled", result.path("cancelled").asBoolean(false));
                if (!normalized.path("cancelled").asBoolean()) {
                    throw new IllegalStateException("取消成功结果缺少成功标识");
                }
                return new NormalizedResult(objectMapper.writeValueAsString(normalized), orderSn);
            }
            orderSn = requiredText(result, "orderSn");
            if (action.getActionType() == AgentActionType.TICKET_PURCHASE && !result.path("tickets").isArray()) {
                throw new IllegalStateException("购票成功结果缺少车票明细");
            }
            if (action.getActionType() == AgentActionType.TICKET_REFUND
                    && !action.getId().equals(requiredText(result, "requestId"))) {
                throw new IllegalStateException("退票结果幂等标识不一致");
            }
            return new NormalizedResult(objectMapper.writeValueAsString(result), orderSn);
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new IllegalStateException("下游对账成功结果无效", exception);
        }
    }

    /**
     * 读取 JSON 中的必填文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 非空文本
     */
    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("对账结果缺少字段: " + field);
        }
        return value;
    }

    /**
     * 锁定读取对账事件。
     *
     * @param eventId 事件标识
     * @return 对账事件
     */
    private ActionReconciliationEntity requireEvent(String eventId) {
        return reconciliationRepository.findLockedById(eventId)
                .orElseThrow(() -> new IllegalStateException("对账事件不存在"));
    }

    /**
     * 锁定读取操作草案。
     *
     * @param actionId 操作标识
     * @return 操作草案
     */
    private ActionDraftEntity requireAction(String actionId) {
        return actionRepository.findLockedById(actionId)
                .orElseThrow(() -> new IllegalStateException("操作草案不存在"));
    }

    /**
     * 锁定读取草案关联的执行审计。
     *
     * @param action 操作草案
     * @return 执行审计
     */
    private ActionExecutionEntity requireExecution(ActionDraftEntity action) {
        return executionRepository.findLockedById(action.getExecutionId())
                .orElseThrow(() -> new IllegalStateException("操作执行记录不存在"));
    }

    /**
     * 原子写回对账事件、操作草案和执行审计。
     *
     * @param event 对账事件
     * @param action 操作草案
     * @param execution 执行审计
     */
    private void updateAll(
            ActionReconciliationEntity event,
            ActionDraftEntity action,
            ActionExecutionEntity execution) {
        reconciliationRepository.updateById(event);
        actionRepository.updateById(action);
        executionRepository.updateById(execution);
    }

    /**
     * 生成持久化结果指纹。
     *
     * @param value 脱敏结果 JSON
     * @return SHA-256 十六进制摘要
     */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * @param safeResultJson 最小脱敏结果
     * @param reference 订单号等业务引用
     */
    private record NormalizedResult(String safeResultJson, String reference) {
    }
}
