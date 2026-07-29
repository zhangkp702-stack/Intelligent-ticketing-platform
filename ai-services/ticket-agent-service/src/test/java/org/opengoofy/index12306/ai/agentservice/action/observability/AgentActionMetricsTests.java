package org.opengoofy.index12306.ai.agentservice.action.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.opengoofy.index12306.ai.agentservice.action.observability.AgentActionMetrics.ConfirmationRejection.EXPIRED;
import static org.opengoofy.index12306.ai.agentservice.action.observability.AgentActionMetrics.ConfirmationRejection.PREVIEW_CHANGED;

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
}
