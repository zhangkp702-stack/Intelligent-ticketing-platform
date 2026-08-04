package org.opengoofy.index12306.ai.agentservice.action.mq;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.ai.agentservice.action.mcp.ActionReconciliationProbe;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * 创建动作对账消费者。
     *
     * @param reconciliationService 对账状态服务
     * @param probe 下游只读状态查询端口
     */
    public ActionReconciliationConsumer(
            ActionReconciliationService reconciliationService,
            ActionReconciliationProbe probe) {
        this.reconciliationService = reconciliationService;
        this.probe = probe;
    }

    /**
     * 对重复消息先做数据库 Inbox 领取，再在事务外查询下游状态。
     *
     * @param message 对账定位消息
     */
    @Override
    public void onMessage(ActionReconciliationMessage message) {
        String workerId = "action-reconcile-" + UUID.randomUUID().toString().substring(0, 8);
        Optional<ActionReconciliationService.WorkItem> claimed = reconciliationService.claim(
                message.eventId(), message.eventVersion(), workerId);
        if (claimed.isEmpty()) {
            LOGGER.info("忽略重复或过期动作对账消息，eventId={}, actionId={}, eventVersion={}",
                    message.eventId(), message.actionId(), message.eventVersion());
            return;
        }

        try {
            // 查询端口只暴露状态工具，绝不重放购票、取消或退票写调用。
            ActionReconciliationService.DownstreamResult result = probe.query(claimed.get());
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
    }
}
