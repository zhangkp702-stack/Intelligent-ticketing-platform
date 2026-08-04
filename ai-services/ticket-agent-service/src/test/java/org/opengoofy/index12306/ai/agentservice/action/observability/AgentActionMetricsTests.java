package org.opengoofy.index12306.ai.agentservice.action.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.opengoofy.index12306.ai.agentservice.action.observability.AgentActionMetrics.ConfirmationRejection.EXPIRED;
import static org.opengoofy.index12306.ai.agentservice.action.observability.AgentActionMetrics.ConfirmationRejection.PREVIEW_CHANGED;
import static org.opengoofy.index12306.ai.agentservice.action.observability.ActionReconciliationMetrics.InboxClaimOutcome.CLAIMED;
import static org.opengoofy.index12306.ai.agentservice.action.observability.ActionReconciliationMetrics.ManualReviewOutcome.ENTERED;
import static org.opengoofy.index12306.ai.agentservice.action.observability.ActionReconciliationMetrics.ManualReviewOutcome.RESUMED;
import static org.opengoofy.index12306.ai.agentservice.action.observability.ActionReconciliationMetrics.ProbeOutcome.PROCESSING;
import static org.opengoofy.index12306.ai.agentservice.action.observability.ActionReconciliationMetrics.PublicationOutcome.FAILED;
import static org.opengoofy.index12306.ai.agentservice.action.observability.ActionReconciliationMetrics.PublicationOutcome.SUCCEEDED;

/**
 * 验证高风险操作指标只使用稳定低基数标签，并正确记录终态和耗时。
 */
class AgentActionMetricsTests {

    /**
     * 验证不同操作类型和终态可以分别统计执行次数与耗时。
     */
    @Test
    void recordsExecutionOutcomeAndDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentActionMetrics metrics = new AgentActionMetrics(registry);

        // 成功和未知结果必须拆分统计，才能直接计算成功率和人工核对比例。
        metrics.recordExecution(
                AgentActionType.TICKET_CANCEL,
                AgentActionStatus.SUCCEEDED,
                System.nanoTime());
        metrics.recordExecution(
                AgentActionType.TICKET_REFUND,
                AgentActionStatus.UNKNOWN,
                System.nanoTime());

        assertThat(registry.get("agent.action.executions")
                .tags("actionType", "TICKET_CANCEL", "outcome", "SUCCEEDED")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get("agent.action.executions")
                .tags("actionType", "TICKET_REFUND", "outcome", "UNKNOWN")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get("agent.action.execution.duration")
                .tags("actionType", "TICKET_CANCEL", "outcome", "SUCCEEDED")
                .timer()
                .count()).isEqualTo(1);
    }

    /**
     * 验证预览变化和确认过期使用固定枚举原因统计。
     */
    @Test
    void recordsStableConfirmationRejectionReasons() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentActionMetrics metrics = new AgentActionMetrics(registry);

        // 拒绝指标不携带 actionId、requestId、异常正文或用户标识。
        metrics.recordConfirmationRejected(AgentActionType.TICKET_REFUND, PREVIEW_CHANGED);
        metrics.recordConfirmationRejected(AgentActionType.TICKET_PURCHASE, EXPIRED);
        metrics.recordConfirmationExpired(AgentActionType.TICKET_PURCHASE);

        assertThat(registry.get("agent.action.confirmation.rejections")
                .tags("actionType", "TICKET_REFUND", "reason", "PREVIEW_CHANGED")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get("agent.action.confirmation.rejections")
                .tags("actionType", "TICKET_PURCHASE", "reason", "EXPIRED")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get("agent.action.confirmation.expirations")
                .tag("actionType", "TICKET_PURCHASE")
                .counter()
                .count()).isEqualTo(1);
    }

    /**
     * 验证对账指标覆盖发布、消费、权威查询、人工恢复和积压快照，且只使用固定标签。
     */
    @Test
    void recordsReconciliationLifecycleAndBacklogMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ActionReconciliationMetrics metrics = new ActionReconciliationMetrics(registry);

        // 通过不同稳定终态验证每条核心指标都可被 Prometheus 聚合和告警规则查询。
        metrics.recordOutboxPublication(SUCCEEDED, System.nanoTime());
        metrics.recordOutboxPublication(FAILED, System.nanoTime());
        metrics.recordInboxClaim(CLAIMED);
        metrics.recordProbe(AgentActionType.TICKET_PURCHASE, PROCESSING, System.nanoTime());
        metrics.recordManualReview(AgentActionType.TICKET_REFUND, ENTERED);
        metrics.recordManualReview(AgentActionType.TICKET_REFUND, RESUMED);
        metrics.recordRecovery(2);
        metrics.refreshBacklog(3, 1, 4, 2, 5, 1);

        assertThat(registry.get("agent.action.reconciliation.outbox.publications")
                .tag("outcome", "SUCCEEDED").counter().count()).isEqualTo(1);
        assertThat(registry.get("agent.action.reconciliation.outbox.publications")
                .tag("outcome", "FAILED").counter().count()).isEqualTo(1);
        assertThat(registry.get("agent.action.reconciliation.inbox.claims")
                .tag("outcome", "CLAIMED").counter().count()).isEqualTo(1);
        assertThat(registry.get("agent.action.reconciliation.probes")
                .tags("actionType", "TICKET_PURCHASE", "outcome", "PROCESSING")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("agent.action.reconciliation.manual_reviews")
                .tags("actionType", "TICKET_REFUND", "outcome", "ENTERED")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("agent.action.reconciliation.recoveries").counter().count()).isEqualTo(2);
        assertThat(registry.get("agent.action.reconciliation.backlog")
                .tags("resource", "command", "status", "MANUAL_REVIEW").gauge().value()).isEqualTo(1);
        assertThat(registry.get("agent.action.reconciliation.backlog")
                .tags("resource", "outbox", "status", "PENDING").gauge().value()).isEqualTo(3);
    }
}
