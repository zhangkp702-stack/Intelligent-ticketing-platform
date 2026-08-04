package org.opengoofy.index12306.ai.agentservice.action.mq;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.ai.agentservice.action.mcp.ActionReconciliationProbe;
import org.opengoofy.index12306.ai.agentservice.action.observability.ActionReconciliationMetrics;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 幂等领取动作对账事件并执行只读权威状态查询。
 */
@Component
@ConditionalOnProperty(
        prefix = "index12306.agent.action.reconciliation.mq",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${index12306.agent.action.reconciliation.mq.topic:index12306_agent_action_reconciliation_topic}",
        selectorExpression = "${index12306.agent.action.reconciliation.mq.tag:RECONCILE}",
        consumerGroup = "${index12306.agent.action.reconciliation.mq.consumer-group:index12306_agent_action_reconciliation_cg}")
public class ActionReconciliationConsumer implements RocketMQListener<ActionReconciliationMessage> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionReconciliationConsumer.class);

    private final ActionReconciliationService reconciliationService;
    private final ActionReconciliationProbe probe;
    private final ActionReconciliationMetrics reconciliationMetrics;

    /**
     * 创建动作对账消费者。
     *
     * @param reconciliationService 对账状态服务
     * @param probe 下游只读状态查询端口
     * @param reconciliationMetrics 对账消费和查询指标记录器
     */
    public ActionReconciliationConsumer(
            ActionReconciliationService reconciliationService,
            ActionReconciliationProbe probe,
            ActionReconciliationMetrics reconciliationMetrics) {
        this.reconciliationService = reconciliationService;
        this.probe = probe;
        this.reconciliationMetrics = reconciliationMetrics;
    }

    /**
     * 对重复消息先校验契约并做数据库 Inbox 领取，再在事务外查询下游状态。
     *
     * @param message 对账定位消息
     */
    @Override
    public void onMessage(ActionReconciliationMessage message) {
        // 消费线程会复用，先建立并在 finally 清理本次消息的日志关联字段。
        MDC.put("reliableEventId", message.eventId());
        MDC.put("agentActionId", message.actionId());
        try {
            String workerId = "action-reconcile-" + UUID.randomUUID().toString().substring(0, 8);
            Optional<ActionReconciliationService.WorkItem> claimed;
            try {
                // 服务层以 Outbox 为权威验证消息，失败时不得创建 Inbox 或触发任何下游访问。
                claimed = reconciliationService.claim(message, workerId);
            } catch (ReconciliationMessageContractException exception) {
                reconciliationMetrics.recordInboxClaim(
                        ActionReconciliationMetrics.InboxClaimOutcome.CONTRACT_REJECTED);
                LOGGER.error("拒绝不匹配的动作对账消息，eventId={}, actionId={}, contractVersion={}",
                        message.eventId(), message.actionId(), message.contractVersion());
                return;
            }
            if (claimed.isEmpty()) {
                reconciliationMetrics.recordInboxClaim(ActionReconciliationMetrics.InboxClaimOutcome.IGNORED);
                LOGGER.info("忽略重复或过期动作对账消息，eventId={}, actionId={}, eventVersion={}",
                        message.eventId(), message.actionId(), message.eventVersion());
                return;
            }
            reconciliationMetrics.recordInboxClaim(ActionReconciliationMetrics.InboxClaimOutcome.CLAIMED);

            try {
                // 查询端口只暴露状态工具，绝不重放购票、取消或退票写调用。
                long startedNanos = System.nanoTime();
                ActionReconciliationService.DownstreamResult result;
                try {
                    result = probe.query(claimed.get());
                    reconciliationMetrics.recordProbe(
                            claimed.get().actionType(), probeOutcome(result), startedNanos);
                } catch (Exception exception) {
                    reconciliationMetrics.recordProbe(
                            claimed.get().actionType(), ActionReconciliationMetrics.ProbeOutcome.EXCEPTION, startedNanos);
                    throw exception;
                }
                reconciliationService.complete(message.eventId(), result);
            } catch (Exception exception) {
                boolean retry = false;
                try {
                    retry = reconciliationService.fail(
                            message.eventId(), exception.getClass().getSimpleName(), exception.getMessage());
                } catch (RuntimeException stateException) {
                    LOGGER.warn("动作对账失败状态记录异常，eventId={}, exceptionType={}",
                            message.eventId(), stateException.getClass().getSimpleName());
                }
                if (retry) {
                    throw exception instanceof RuntimeException runtimeException
                            ? runtimeException : new IllegalStateException("动作对账查询失败", exception);
                }
            }
        } finally {
            // RocketMQ 消费线程会复用，不能让下一条消息继承本条的关联标识。
            MDC.remove("reliableEventId");
            MDC.remove("agentActionId");
        }
    }

    /**
     * 将下游稳定状态转换为对账指标标签，避免将下游原始文本暴露给指标系统。
     *
     * @param result 下游权威查询结果
     * @return 固定低基数查询结果标签
     */
    private ActionReconciliationMetrics.ProbeOutcome probeOutcome(
            ActionReconciliationService.DownstreamResult result) {
        // 枚举名称保持一一对应，但显式映射能避免下游新增状态时静默扩散到指标标签。
        return switch (result.status()) {
            case SUCCEEDED -> ActionReconciliationMetrics.ProbeOutcome.SUCCEEDED;
            case FAILED -> ActionReconciliationMetrics.ProbeOutcome.FAILED;
            case PROCESSING -> ActionReconciliationMetrics.ProbeOutcome.PROCESSING;
        };
    }
}
