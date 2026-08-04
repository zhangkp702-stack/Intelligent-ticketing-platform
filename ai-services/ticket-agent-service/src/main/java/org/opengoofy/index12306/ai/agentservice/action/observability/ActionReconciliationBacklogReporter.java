package org.opengoofy.index12306.ai.agentservice.action.observability;

import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionStateService;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxStatus;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxStatus;
import org.opengoofy.index12306.framework.starter.reliablecommand.store.ReliableCommandStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时读取可靠命令、Outbox 和 Inbox 的低频状态快照，供 Prometheus 告警持续积压。
 */
@Component
public class ActionReconciliationBacklogReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionReconciliationBacklogReporter.class);

    private final ReliableEventStore reliableEventStore;
    private final ReliableCommandStore reliableCommandStore;
    private final ActionReconciliationMetrics metrics;

    /**
     * 创建动作对账积压采集器。
     *
     * @param reliableEventStore 通用 Outbox 与 Inbox 查询接口
     * @param reliableCommandStore 通用可靠命令查询接口
     * @param metrics 对账指标记录器
     */
    public ActionReconciliationBacklogReporter(
            ReliableEventStore reliableEventStore,
            ReliableCommandStore reliableCommandStore,
            ActionReconciliationMetrics metrics) {
        this.reliableEventStore = reliableEventStore;
        this.reliableCommandStore = reliableCommandStore;
        this.metrics = metrics;
    }

    /**
     * 刷新可靠状态快照；读取失败时保留上一轮值并单独记录采集失败。
     */
    @Scheduled(fixedDelayString = "${index12306.agent.action.reconciliation.observability.backlog-refresh-interval-millis:15000}")
    public void refresh() {
        try {
            // 所有查询都限制在动作对账的固定 namespace 和消费者，避免跨业务域混合计数。
            metrics.refreshBacklog(
                    reliableEventStore.countEventsByStatus(
                            ActionReconciliationService.EVENT_NAMESPACE, ReliableOutboxStatus.PENDING),
                    reliableEventStore.countEventsByStatus(
                            ActionReconciliationService.EVENT_NAMESPACE, ReliableOutboxStatus.PUBLISHING),
                    reliableEventStore.countConsumptionsByStatus(
                            ActionReconciliationService.EVENT_NAMESPACE,
                            ActionReconciliationService.CONSUMER_NAME,
                            ReliableInboxStatus.RETRY_WAIT),
                    reliableEventStore.countConsumptionsByStatus(
                            ActionReconciliationService.EVENT_NAMESPACE,
                            ActionReconciliationService.CONSUMER_NAME,
                            ReliableInboxStatus.FAILED),
                    reliableCommandStore.countByStatus(
                            ActionStateService.COMMAND_NAMESPACE, ReliableCommandStatus.UNKNOWN),
                    reliableCommandStore.countByStatus(
                            ActionStateService.COMMAND_NAMESPACE, ReliableCommandStatus.MANUAL_REVIEW));
        } catch (RuntimeException exception) {
            // 保留最近一次成功快照，调用方可结合失败计数判断积压数是否已过期。
            metrics.recordBacklogRefreshFailure();
            LOGGER.warn("动作对账积压指标刷新失败，exceptionType={}", exception.getClass().getSimpleName());
        }
    }
}
