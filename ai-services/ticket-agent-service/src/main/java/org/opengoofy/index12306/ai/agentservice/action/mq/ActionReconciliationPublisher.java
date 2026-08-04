package org.opengoofy.index12306.ai.agentservice.action.mq;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.opengoofy.index12306.ai.agentservice.action.observability.ActionReconciliationMetrics;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 扫描事务 Outbox 并可靠发布 UNKNOWN 操作对账事件。
 */
@Component
@ConditionalOnProperty(
        prefix = "index12306.agent.action.reconciliation.mq",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ActionReconciliationPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionReconciliationPublisher.class);
    private static final String EVENT_ID_HEADER = "x-reliable-event-id";
    private static final String EVENT_NAMESPACE_HEADER = "x-reliable-event-namespace";
    private static final String EVENT_VERSION_HEADER = "x-reliable-event-version";
    private static final String CONTRACT_VERSION_HEADER = "x-reliable-contract-version";

    private final ActionReconciliationService reconciliationService;
    private final RocketMQTemplate rocketMQTemplate;
    private final String destination;
    private final long sendTimeoutMillis;
    private final ActionReconciliationMetrics reconciliationMetrics;
    private final String publisherId = "action-reconciliation-publisher-" + UUID.randomUUID();

    /**
     * 创建对账 Outbox 发布器。
     *
     * @param reconciliationService 对账状态服务
     * @param rocketMQTemplate RocketMQ 客户端
     * @param topic MQ 主题
     * @param tag MQ 标签
     * @param sendTimeoutMillis 发送超时毫秒数
     * @param reconciliationMetrics 对账发布指标记录器
     */
    public ActionReconciliationPublisher(
            ActionReconciliationService reconciliationService,
            RocketMQTemplate rocketMQTemplate,
            @Value("${index12306.agent.action.reconciliation.mq.topic:index12306_agent_action_reconciliation_topic}") String topic,
            @Value("${index12306.agent.action.reconciliation.mq.tag:RECONCILE}") String tag,
            @Value("${index12306.agent.action.reconciliation.mq.send-timeout-millis:2000}") long sendTimeoutMillis,
            ActionReconciliationMetrics reconciliationMetrics) {
        this.reconciliationService = reconciliationService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.destination = topic + ":" + tag;
        this.sendTimeoutMillis = sendTimeoutMillis;
        this.reconciliationMetrics = reconciliationMetrics;
    }

    /**
     * 恢复到期事件并同步确认发布有限批次的待发送事件。
     */
    @Scheduled(fixedDelayString = "${index12306.agent.action.reconciliation.mq.publish-interval-millis:1000}")
    public void publishPending() {
        int recovered = reconciliationService.recoverExpired();
        reconciliationMetrics.recordRecovery(recovered);
        if (recovered > 0) {
            LOGGER.warn("恢复过期动作对账事件，count={}", recovered);
        }
        // 每条事件先取得通用 Outbox 发布围栏，避免多实例无约束地同时扫描发送。
        for (ActionReconciliationService.PendingEvent event
                : reconciliationService.claimPendingEvents(publisherId)) {
            // 关联日志使用业务标识，指标仍只使用稳定枚举标签。
            MDC.put("reliableEventId", event.eventId());
            MDC.put("agentActionId", event.actionId());
            long startedNanos = System.nanoTime();
            try {
                ActionReconciliationMessage payload = ActionReconciliationMessage.from(event);
                Message<ActionReconciliationMessage> message = MessageBuilder.withPayload(payload)
                        // Broker 索引和自定义头都使用 Outbox 快照，便于排障时按事件而不是模糊的业务日志关联。
                        .setHeader(MessageConst.PROPERTY_KEYS, event.eventId() + ":" + event.actionId())
                        .setHeader(EVENT_ID_HEADER, event.eventId())
                        .setHeader(EVENT_NAMESPACE_HEADER, ActionReconciliationService.EVENT_NAMESPACE)
                        .setHeader(EVENT_VERSION_HEADER, Long.toString(event.eventVersion()))
                        .setHeader(CONTRACT_VERSION_HEADER,
                                Integer.toString(ActionReconciliationMessage.CURRENT_CONTRACT_VERSION))
                        .build();
                SendResult result = rocketMQTemplate.syncSend(destination, message, sendTimeoutMillis);
                reconciliationService.markPublished(event, result.getMsgId());
                reconciliationMetrics.recordOutboxPublication(
                        ActionReconciliationMetrics.PublicationOutcome.SUCCEEDED, startedNanos);
            } catch (RuntimeException exception) {
                // 发布失败必须释放当前围栏并安排下一轮，否则只能等待租约恢复。
                try {
                    reconciliationService.markPublishFailed(
                            event, exception.getClass().getSimpleName(), exception.getMessage());
                } catch (RuntimeException stateException) {
                    // 围栏已被其他实例恢复时只记录竞争结果，不能中断本批其他事件发布。
                    LOGGER.warn("动作对账发布失败状态记录异常，eventId={}, exceptionType={}",
                            event.eventId(), stateException.getClass().getSimpleName());
                }
                LOGGER.warn("动作对账事件发布失败，eventId={}, actionId={}, exceptionType={}",
                        event.eventId(), event.actionId(), exception.getClass().getSimpleName());
                reconciliationMetrics.recordOutboxPublication(
                        ActionReconciliationMetrics.PublicationOutcome.FAILED, startedNanos);
            } finally {
                // 发布线程会复用，必须清理 MDC，避免下一条事件错误继承关联标识。
                MDC.remove("reliableEventId");
                MDC.remove("agentActionId");
            }
        }
    }
}
