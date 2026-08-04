package org.opengoofy.index12306.ai.agentservice.action.dao.entity;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.enums.ActionExecutionOutcome;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Action 执行租约和 fencing token 的实体状态边界。
 */
class ActionExecutionEntityTests {

    /**
     * 验证从未领取执行权的排队记录可以明确结束且没有下游写调用语义。
     */
    @Test
    void abandonedQueueEndsAsDefiniteFailure() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        ActionExecutionEntity execution = ActionExecutionEntity.queue(
                "action-1", "request-1", "key-1", now);

        // QUEUED 没有 owner 和 fencing token，恢复时无需进入结果未知对账。
        execution.abandonQueued("ACTION_EXECUTION_NOT_STARTED", now.plusSeconds(60));
        assertThat(execution.getOutcome()).isEqualTo(ActionExecutionOutcome.FAILED);
        assertThat(execution.getLeaseOwner()).isNull();
        assertThat(execution.getFailureCategory()).isEqualTo("ACTION_EXECUTION_NOT_STARTED");
    }

    /**
     * 验证错误执行者不能提交终态。
     */
    @Test
    void rejectsCompletionFromStaleOwnerOrToken() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        ActionExecutionEntity execution = ActionExecutionEntity.queue(
                "action-1", "request-1", "key-1", now);
        long token = execution.claim("worker-1", now.plusSeconds(60), now);

        // owner 或 fencing token 任一不匹配，都不能覆盖当前执行者的结果。
        assertThatThrownBy(() -> execution.succeed(
                "worker-2", token, "order-1", "fingerprint", now.plusSeconds(1)))
                .hasMessageContaining("执行权已经失效");
        assertThatThrownBy(() -> execution.succeed(
                "worker-1", token + 1, "order-1", "fingerprint", now.plusSeconds(1)))
                .hasMessageContaining("执行权已经失效");
        assertThat(execution.getOutcome()).isEqualTo(ActionExecutionOutcome.STARTED);
    }

    /**
     * 验证租约过期只转 UNKNOWN，迟到成功不能再覆盖恢复状态。
     */
    @Test
    void expiredLeaseMovesToUnknownWithoutReplay() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        ActionExecutionEntity execution = ActionExecutionEntity.queue(
                "action-1", "request-1", "key-1", now);
        long token = execution.claim("worker-1", now.plusSeconds(1), now);

        // 恢复器只能声明结果未知，不能重新领取并调用真实写接口。
        execution.recoverExpired("ACTION_EXECUTION_LEASE_EXPIRED", now.plusSeconds(2));
        assertThat(execution.getOutcome()).isEqualTo(ActionExecutionOutcome.UNKNOWN);
        assertThat(execution.getLeaseOwner()).isNull();
        assertThatThrownBy(() -> execution.succeed(
                "worker-1", token, "order-1", "fingerprint", now.plusSeconds(3)))
                .hasMessageContaining("已经结束");
    }
}
