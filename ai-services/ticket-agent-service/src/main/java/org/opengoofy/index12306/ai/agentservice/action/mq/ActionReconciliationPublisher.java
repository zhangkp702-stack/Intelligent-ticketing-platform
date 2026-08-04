package org.opengoofy.index12306.ai.agentservice.action.mq;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

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

    private final ActionReconciliationService reconciliationService;
    private final RocketMQTemplate rocketMQTemplate;
    private final Clock clock;
    private final String destination;
    private final long sendTimeoutMillis;

    /**
     * 创建对账 Outbox 发布器。
     *
     * @param reconciliationService 对账状态服务
     * @param rocketMQTemplate RocketMQ 客户端
     * @param clock 统一时钟
     * @param topic MQ 主题
     * @param tag MQ 标签
     * @param sendTimeoutMillis 发送超时毫秒数
     */
    public ActionReconciliationPublisher(
            ActionReconciliationService reconciliationService,
            RocketMQTemplate rocketMQTemplate,
            Clock clock,
            @Value("${index12306.agent.action.reconciliation.mq.topic:index12306_agent_action_reconciliation_topic}") String topic,
            @Value("${index12306.agent.action.reconciliation.mq.tag:RECONCILE}") String tag,
            @Value("${index12306.agent.action.reconciliation.mq.send-timeout-millis:2000}") long sendTimeoutMillis) {
        this.reconciliationService = reconciliationService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.clock = clock;
        this.destination = topic + ":" + tag;
        this.sendTimeoutMillis = sendTimeoutMillis;
    }

    /**
     * 恢复到期事件并同步确认发布有限批次的待发送事件。
     */
    @Scheduled(fixedDelayString = "${index12306.agent.action.reconciliation.mq.publish-interval-millis:1000}")
    public void publishPending() {
        int recovered = reconciliationService.recoverExpired();
        if (recovered > 0) {
            LOGGER.warn("恢复过期动作对账事件，count={}", recovered);
        }
        // MQ 发送失败时事件保持 PENDING，下一轮继续发布且消费者按数据库事件去重。
        for (ActionReconciliationService.PendingEvent event : reconciliationService.pendingEvents()) {
            try {
                ActionReconciliationMessage payload = new ActionReconciliationMessage(
                        event.eventId(), event.actionId(), event.eventVersion(), clock.instant());
                Message<ActionReconciliationMessage> message = MessageBuilder.withPayload(payload)
                        .setHeader(MessageConst.PROPERTY_KEYS, event.actionId() + ":" + event.eventVersion())
                        .build();
                SendResult result = rocketMQTemplate.syncSend(destination, message, sendTimeoutMillis);
                reconciliationService.markPublished(event.eventId(), event.eventVersion(), result.getMsgId());
            } catch (RuntimeException exception) {
                LOGGER.warn("动作对账事件发布失败，eventId={}, actionId={}, exceptionType={}",
                        event.eventId(), event.actionId(), exception.getClass().getSimpleName());
            }
        }
    }
}
