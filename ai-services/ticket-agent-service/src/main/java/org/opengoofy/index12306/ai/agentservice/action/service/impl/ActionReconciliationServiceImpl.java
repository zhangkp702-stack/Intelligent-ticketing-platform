package org.opengoofy.index12306.ai.agentservice.action.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opengoofy.index12306.ai.agentservice.action.config.ActionReconciliationProperties;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.dao.repository.ActionDraftRepository;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.opengoofy.index12306.ai.agentservice.action.mq.ActionReconciliationMessage;
import org.opengoofy.index12306.ai.agentservice.action.mq.ReconciliationMessageContractException;
import org.opengoofy.index12306.ai.agentservice.action.observability.ActionReconciliationMetrics;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionStateService;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableConsumptionResult;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventDefinition;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxStatus;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.store.ReliableCommandStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 以数据库事务协调 UNKNOWN 操作的 Outbox 发布、Inbox 领取和权威结果回写。
 */
@Service
public class ActionReconciliationServiceImpl implements ActionReconciliationService {

    private static final String EVENT_TYPE = "ACTION_RECONCILIATION_REQUESTED";
    private static final Duration PUBLISH_RETRY_DELAY = Duration.ofSeconds(1);
    private static final int BATCH_SIZE = 100;

    private final ActionReconciliationProperties properties;
    private final ReliableEventStore reliableEventStore;
    private final ActionDraftRepository actionRepository;
    private final ReliableCommandStore reliableCommandStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ActionReconciliationMetrics reconciliationMetrics;

    /**
     * 创建动作对账事务服务。
     *
     * @param properties 对账重试配置
     * @param reliableEventStore 通用可靠 Outbox 和 Inbox 存储
     * @param actionRepository 操作草案仓储
     * @param reliableCommandStore 通用可靠命令仓储
     * @param objectMapper JSON 解析器
     * @param clock 统一时钟
     * @param reconciliationMetrics 对账生命周期指标记录器
     */
    public ActionReconciliationServiceImpl(
            ActionReconciliationProperties properties,
            ReliableEventStore reliableEventStore,
            ActionDraftRepository actionRepository,
            ReliableCommandStore reliableCommandStore,
            ObjectMapper objectMapper,
            Clock clock,
            ActionReconciliationMetrics reconciliationMetrics) {
        this.properties = properties;
        this.reliableEventStore = reliableEventStore;
        this.actionRepository = actionRepository;
        this.reliableCommandStore = reliableCommandStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.reconciliationMetrics = reconciliationMetrics;
    }

    /**
     * 在 UNKNOWN 状态提交事务内创建唯一对账 Outbox 事件。
     *
     * @param actionId 操作草案标识
     */
    @Transactional
    @Override
    public void request(String actionId) {
        // actionId 同时作为事件标识和业务去重键，重复异常处理只会复用同一 Outbox。
        ReliableEventDefinition definition = new ReliableEventDefinition(
                eventKey(actionId), actionId, EVENT_TYPE, actionId, actionId, 1L);
        reliableEventStore.enqueue(definition, clock.instant());
    }

    /**
     * 使用通用发布租约领取本轮可发送的 Outbox 事件。
     *
     * @param publisherId 发布实例标识
     * @return 最多一百条待发布事件
     */
    @Override
    public List<PendingEvent> claimPendingEvents(String publisherId) {
        Instant now = clock.instant();
        // 发布租约只覆盖 MQ 发送窗口，发送期间不持有数据库事务和行锁。
        return reliableEventStore.claimPublishable(
                EVENT_NAMESPACE, publisherId, now, now.plus(properties.leaseDuration()), BATCH_SIZE)
                .stream()
                .map(event -> new PendingEvent(
                        event.key().eventId(), event.aggregateId(), event.eventVersion(),
                        event.createdAt(), event.lease()))
                .toList();
    }

    /**
     * 在 MQ 确认接收后持久化消息标识。
     *
     * @param event 已领取事件
     * @param messageId MQ 消息标识
     */
    @Override
    public void markPublished(PendingEvent event, String messageId) {
        // owner 和 fencing token 共同拒绝租约恢复后的迟到发布确认。
        boolean saved = reliableEventStore.markPublished(
                eventKey(event.eventId()), event.lease(), event.eventVersion(), messageId, clock.instant());
        if (!saved) {
            throw new IllegalStateException("动作对账事件发布权已经失效");
        }
    }

    /**
     * 释放失败发布租约并安排短延迟重试。
     *
     * @param event 已领取事件
     * @param category 稳定失败分类
     * @param safeMessage 安全失败摘要
     */
    @Override
    public void markPublishFailed(PendingEvent event, String category, String safeMessage) {
        Instant now = clock.instant();
        boolean saved = reliableEventStore.markPublishFailed(
                eventKey(event.eventId()), event.lease(), event.eventVersion(),
                category, safeMessage, now.plus(PUBLISH_RETRY_DELAY), now);
        if (!saved) {
            throw new IllegalStateException("动作对账事件发布权已经失效");
        }
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
        // 发布进程在发送前宕机时释放过期发布围栏；发送确认丢失允许产生可去重的重复消息。
        int recovered = reliableEventStore.recoverExpiredPublications(
                EVENT_NAMESPACE, now, BATCH_SIZE);
        // 先由通用状态机回收过期 PROCESSING/RECONCILING，绝不重新调用真实写接口。
        reliableCommandStore.recoverExpiredLeases(
                ActionStateService.COMMAND_NAMESPACE, now, CONSUMER_NAME, 100);
        List<ReliableInboxRecord> candidates = reliableEventStore.findExpiredConsumptions(
                EVENT_NAMESPACE, CONSUMER_NAME, now, BATCH_SIZE);
        // 只有通用命令也已回到 UNKNOWN，才允许释放 Inbox 消费围栏并重新发布只读查询。
        for (ReliableInboxRecord inbox : candidates) {
            ReliableOutboxRecord event = requireEvent(inbox.key().eventKey().eventId());
            ActionDraftEntity action = requireAction(event.aggregateId());
            if (requireCommand(action).status() != ReliableCommandStatus.UNKNOWN) {
                // 通用命令租约尚未到期时继续等待，不能只恢复 Outbox 而提前释放围栏。
                continue;
            }
            Optional<ReliableConsumptionResult> recoveredConsumption = reliableEventStore.retryConsumption(
                    inbox.key(), requireInboxLease(inbox), "CONSUMER_LEASE_EXPIRED",
                    "动作对账消费者租约已过期", now, now);
            if (recoveredConsumption.isEmpty()) {
                // 其他实例已经恢复当前 Inbox 时跳过，不能让一次围栏竞争回滚整批任务。
                continue;
            }
            ReliableConsumptionResult result = recoveredConsumption.get();
            if (result.retryScheduled()) {
                action.reconciliationPending(now);
            } else {
                moveToManualReview(action, now, "RECONCILIATION_EXHAUSTED");
            }
            actionRepository.updateById(action);
            recovered++;
        }
        return recovered;
    }

    /**
     * 以数据库行锁幂等领取消息，并把操作和执行审计一起转入 RECONCILING。
     *
     * @param message 已反序列化的对账消息
     * @param workerId 消费实例标识
     * @return 成功领取时的不可变工作项
     */
    @Transactional
    @Override
    public Optional<WorkItem> claim(ActionReconciliationMessage message, String workerId) {
        ReliableOutboxRecord event = requireEvent(message.eventId());
        // 在创建 Inbox 前验证消息的所有业务定位字段，防止错误主题或篡改载荷领取其他动作。
        assertMatchesPersistedEvent(message, event);
        Instant now = clock.instant();
        Optional<ReliableInboxRecord> claimed = reliableEventStore.claimConsumption(
                event.key(), message.eventVersion(), CONSUMER_NAME, workerId,
                now, now.plus(properties.leaseDuration()), properties.maxAttempts());
        if (claimed.isEmpty()) {
            return Optional.empty();
        }

        // Inbox、草案和通用命令在同一事务进入运行态，后续提交必须同时通过两类围栏。
        ActionDraftEntity action = requireAction(event.aggregateId());
        reliableCommandStore.claimReconciliation(
                ActionStateService.commandKey(action.getId()), workerId,
                now, now.plus(properties.leaseDuration()))
                .orElseThrow(() -> new IllegalStateException("操作可靠命令无法领取对账权"));
        action.beginReconciliation(now);
        actionRepository.updateById(action);
        return Optional.of(new WorkItem(
                event.key().eventId(), event.eventVersion(), action.getId(), action.getActionType(),
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
        ReliableOutboxRecord event = requireEvent(eventId);
        ReliableInboxRecord inbox = requireProcessingInbox(event.key());
        ActionDraftEntity action = requireAction(event.aggregateId());
        ReliableCommandRecord command = requireCommand(action);
        Instant now = clock.instant();
        assertSameOperation(action, result);

        if (result.status() == DownstreamStatus.PROCESSING) {
            // 下游仍在处理中不代表失败，通过相同 Outbox 事件延迟查询或转人工。
            Instant nextRetryAt = now.plus(properties.retryDelay().multipliedBy(inbox.attemptCount()));
            ReliableConsumptionResult consumption = reliableEventStore.retryConsumption(
                    inbox.key(), requireInboxLease(inbox), "DOWNSTREAM_PROCESSING",
                    "下游操作仍在处理中", nextRetryAt, now)
                    .orElseThrow(() -> new IllegalStateException("动作对账 Inbox 执行权已经失效"));
            finishPendingOrManual(command, action, consumption.retryScheduled(),
                    "DOWNSTREAM_PROCESSING", "下游操作仍在处理中", nextRetryAt, now);
            actionRepository.updateById(action);
            return false;
        }
        if (result.status() == DownstreamStatus.FAILED) {
            // 只有下游操作事实表明确失败，Agent 才允许结束为 FAILED。
            boolean saved = reliableCommandStore.reconcileFailed(
                    command.key(), requireLease(command),
                    "DOWNSTREAM_CONFIRMED_FAILED", result.failureMessage(),
                    "DOWNSTREAM_STATUS:FAILED", now);
            requireSaved(saved);
            action.reconcileFailed("DOWNSTREAM_CONFIRMED_FAILED", now);
            requireInboxSaved(reliableEventStore.completeConsumption(
                    inbox.key(), requireInboxLease(inbox), now));
            actionRepository.updateById(action);
            return true;
        }

        // 成功结果必须重新按动作类型白名单化，不能直接保存任意下游 JSON。
        NormalizedResult normalized = normalizeSuccess(action, result.safeResultJson());
        boolean saved = reliableCommandStore.reconcileSucceeded(
                command.key(), requireLease(command), normalized.safeResultJson(),
                normalized.reference(), "DOWNSTREAM_STATUS:SUCCEEDED", now);
        requireSaved(saved);
        action.reconcileSucceeded(normalized.safeResultJson(), normalized.reference(), now);
        requireInboxSaved(reliableEventStore.completeConsumption(
                inbox.key(), requireInboxLease(inbox), now));
        actionRepository.updateById(action);
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
        ReliableOutboxRecord event = requireEvent(eventId);
        ReliableInboxRecord inbox = requireProcessingInbox(event.key());
        ActionDraftEntity action = requireAction(event.aggregateId());
        ReliableCommandRecord command = requireCommand(action);
        Instant now = clock.instant();

        // 查询失败不得重放真实写请求，只能安排下一次只读对账或人工处理。
        Instant nextRetryAt = now.plus(properties.retryDelay().multipliedBy(inbox.attemptCount()));
        ReliableConsumptionResult consumption = reliableEventStore.retryConsumption(
                inbox.key(), requireInboxLease(inbox), category, safeMessage, nextRetryAt, now)
                .orElseThrow(() -> new IllegalStateException("动作对账 Inbox 执行权已经失效"));
        finishPendingOrManual(command, action, consumption.retryScheduled(),
                category, safeMessage, nextRetryAt, now);
        actionRepository.updateById(action);
        return consumption.retryScheduled();
    }

    /**
     * 将人工复核中的动作重新投递为同一条只读对账任务，并保存操作员审计。
     *
     * @param actionId 操作草案标识
     * @param operatorId 人工操作员标识
     * @param reason 人工重新核对原因
     * @return 重新调度后的动作摘要
     */
    @Transactional
    @Override
    public ManualReviewResumeResult resumeManualReview(String actionId, String operatorId, String reason) {
        ActionDraftEntity action = requireAction(actionId);
        if (action.getStatus() != AgentActionStatus.MANUAL_REVIEW) {
            throw new IllegalStateException("操作不处于人工复核状态");
        }
        ReliableOutboxRecord event = requireEvent(actionId);
        if (!action.getId().equals(event.aggregateId())) {
            throw new IllegalStateException("人工复核事件与操作草案不匹配");
        }
        ReliableCommandRecord command = requireCommand(action);
        if (command.status() != ReliableCommandStatus.MANUAL_REVIEW) {
            throw new IllegalStateException("可靠命令不处于人工复核状态");
        }
        Instant now = clock.instant();

        // 先重启通用命令调度并写入操作员审计，原始写命令的指纹、归属和结果均不可修改。
        requireSaved(reliableCommandStore.resumeManualReview(
                command.key(), operatorId, reason, now, now));
        // 再重置同一事件的失败 Inbox，Outbox 会由发布器重新发送，消费者仍只调用权威查询工具。
        boolean resumed = reliableEventStore.resumeFailedConsumption(
                new ReliableInboxKey(event.key(), CONSUMER_NAME),
                "MANUAL_RECONCILIATION_REQUEUED", reason, now, now);
        if (!resumed) {
            throw new IllegalStateException("动作对账 Inbox 不处于可人工恢复状态");
        }
        action.resumeManualReview("MANUAL_RECONCILIATION_REQUEUED", now);
        actionRepository.updateById(action);
        // 只统计受权人工重启次数，完整操作员和原因已由可靠命令审计持久化。
        reconciliationMetrics.recordManualReview(
                action.getActionType(), ActionReconciliationMetrics.ManualReviewOutcome.RESUMED);
        return new ManualReviewResumeResult(action.getId(), action.getStatus(), now);
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
     * 校验 MQ 消息与本地权威 Outbox 是否描述同一条对账事件。
     *
     * @param message Broker 传入的消息契约
     * @param event 数据库中的权威 Outbox 记录
     */
    private void assertMatchesPersistedEvent(
            ActionReconciliationMessage message,
            ReliableOutboxRecord event) {
        // 事件 ID 命中后仍必须比较业务域、类型、聚合、版本，避免仅凭一个字符串跨域误消费。
        if (!EVENT_NAMESPACE.equals(message.eventNamespace())
                || !EVENT_NAMESPACE.equals(event.key().namespace())
                || !ActionReconciliationMessage.EVENT_TYPE.equals(message.eventType())
                || !ActionReconciliationMessage.EVENT_TYPE.equals(event.eventType())
                || !event.aggregateId().equals(message.actionId())
                || event.eventVersion() != message.eventVersion()) {
            throw new ReconciliationMessageContractException("Reconciliation message does not match persisted outbox event");
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
     * 读取通用 Outbox 对账事件。
     *
     * @param eventId 事件标识
     * @return Outbox 事件
     */
    private ReliableOutboxRecord requireEvent(String eventId) {
        return reliableEventStore.findEvent(eventKey(eventId))
                .orElseThrow(() -> new IllegalStateException("对账事件不存在"));
    }

    /**
     * 创建动作对账事件主键。
     *
     * @param eventId 事件标识
     * @return 通用事件主键
     */
    private ReliableEventKey eventKey(String eventId) {
        return new ReliableEventKey(EVENT_NAMESPACE, eventId);
    }

    /**
     * 读取当前消费者已经领取的 PROCESSING Inbox。
     *
     * @param eventKey 事件主键
     * @return 当前运行中的 Inbox
     */
    private ReliableInboxRecord requireProcessingInbox(ReliableEventKey eventKey) {
        ReliableInboxRecord inbox = reliableEventStore.findConsumption(
                new ReliableInboxKey(eventKey, CONSUMER_NAME))
                .orElseThrow(() -> new IllegalStateException("动作对账 Inbox 不存在"));
        if (inbox.status() != ReliableInboxStatus.PROCESSING) {
            throw new IllegalStateException("动作对账 Inbox 不处于运行状态");
        }
        return inbox;
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
     * 读取草案关联的通用可靠命令。
     *
     * @param action 操作草案
     * @return 当前权威命令记录
     */
    private ReliableCommandRecord requireCommand(ActionDraftEntity action) {
        return reliableCommandStore.find(ActionStateService.commandKey(action.getId()))
                .orElseThrow(() -> new IllegalStateException("操作可靠命令不存在"));
    }

    /**
     * 提取对账记录当前持有的围栏租约。
     *
     * @param command 已领取的对账命令
     * @return owner 和 fencing token
     */
    private ReliableCommandLease requireLease(ReliableCommandRecord command) {
        return Objects.requireNonNull(command.lease(), "对账命令缺少租约");
    }

    /**
     * 提取 Inbox 当前持有的消费围栏租约。
     *
     * @param inbox 已领取 Inbox
     * @return owner 和 fencing token
     */
    private ReliableEventLease requireInboxLease(ReliableInboxRecord inbox) {
        return Objects.requireNonNull(inbox.lease(), "动作对账 Inbox 缺少租约");
    }

    /**
     * 将无结论查询安排为下一次只读对账，达到上限时显式转人工处理。
     *
     * @param command 已领取的对账命令
     * @param action 当前对账草案
     * @param retry 是否继续自动查询
     * @param category 稳定分类
     * @param message 安全失败摘要
     * @param nextRetryAt 下一次查询时间
     * @param now 当前时间
     */
    private void finishPendingOrManual(
            ReliableCommandRecord command,
            ActionDraftEntity action,
            boolean retry,
            String category,
            String message,
            Instant nextRetryAt,
            Instant now) {
        ReliableCommandStatus target = retry
                ? ReliableCommandStatus.UNKNOWN : ReliableCommandStatus.MANUAL_REVIEW;
        boolean saved = reliableCommandStore.finishReconciliation(
                command.key(), requireLease(command), target, category, message, category,
                retry ? Objects.requireNonNull(nextRetryAt, "nextRetryAt") : null, now);
        requireSaved(saved);
        if (retry) {
            action.reconciliationPending(now);
        } else {
            action.requireManualReview("RECONCILIATION_EXHAUSTED", now);
            // 自动对账耗尽后只记录低基数生命周期事件，操作员和失败细节保留在审计表。
            reconciliationMetrics.recordManualReview(
                    action.getActionType(), ActionReconciliationMetrics.ManualReviewOutcome.ENTERED);
        }
    }

    /**
     * 将已经没有自动重试机会的 UNKNOWN 操作转为人工处理。
     *
     * @param action 操作草案
     * @param now 当前时间
     * @param category 稳定人工处理分类
     */
    private void moveToManualReview(
            ActionDraftEntity action,
            Instant now,
            String category) {
        ReliableCommandRecord command = reliableCommandStore.claimReconciliation(
                ActionStateService.commandKey(action.getId()), CONSUMER_NAME,
                now, now.plus(properties.leaseDuration()))
                .orElseThrow(() -> new IllegalStateException("操作可靠命令无法转人工处理"));
        boolean saved = reliableCommandStore.finishReconciliation(
                command.key(), requireLease(command), ReliableCommandStatus.MANUAL_REVIEW,
                category, category, category, null, now);
        requireSaved(saved);
        if (action.getStatus() == AgentActionStatus.UNKNOWN) {
            action.beginReconciliation(now);
        }
        action.requireManualReview(category, now);
        // 租约恢复发现重试已耗尽时，同样需要暴露人工复核入口量。
        reconciliationMetrics.recordManualReview(
                action.getActionType(), ActionReconciliationMetrics.ManualReviewOutcome.ENTERED);
    }

    /**
     * 校验通用命令围栏更新成功。
     *
     * @param saved 围栏状态迁移结果
     */
    private void requireSaved(boolean saved) {
        if (!saved) {
            throw new IllegalStateException("操作对账执行权已经失效");
        }
    }

    /**
     * 校验 Inbox 围栏更新成功。
     *
     * @param saved Inbox 状态迁移结果
     */
    private void requireInboxSaved(boolean saved) {
        if (!saved) {
            throw new IllegalStateException("动作对账 Inbox 执行权已经失效");
        }
    }

    /**
     * @param safeResultJson 最小脱敏结果
     * @param reference 订单号等业务引用
     */
    private record NormalizedResult(String safeResultJson, String reference) {
    }
}
