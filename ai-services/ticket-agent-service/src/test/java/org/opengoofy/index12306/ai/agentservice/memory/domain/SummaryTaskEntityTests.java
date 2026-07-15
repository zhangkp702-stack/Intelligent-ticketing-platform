package org.opengoofy.index12306.ai.agentservice.memory.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证摘要任务在无需真实线程等待时的领取、退避和最终失败状态流转。
 */
class SummaryTaskEntityTests {

    /**
     * 验证失败任务按尝试次数延迟重试，并在达到最大次数后停止分发。
     */
    @Test
    void failedTaskRetriesWithDelayAndEventuallyStops() {
        Instant createdAt = Instant.parse("2026-07-15T00:00:00Z");
        Duration retryDelay = Duration.ofSeconds(30);
        SummaryTaskEntity task = SummaryTaskEntity.pending(
                "conversation", "topic", 1, 2, 1, 3, createdAt);

        // 首次领取失败后进入等待状态，重试时间到达前不可再次分发。
        task.claim("worker-1", createdAt, Duration.ofMinutes(2));
        task.fail("TIMEOUT", "line one\nline two", createdAt.plusSeconds(5), retryDelay);
        assertThat(task.getStatus()).isEqualTo(SummaryTaskStatus.RETRY_WAIT);
        assertThat(task.getFailureMessage()).isEqualTo("line one line two");
        assertThat(task.isDispatchable(createdAt.plusSeconds(34))).isFalse();
        assertThat(task.isDispatchable(createdAt.plusSeconds(35))).isTrue();

        // 第二次失败等待时间按尝试次数增长，第三次失败后进入不可恢复状态。
        task.claim("worker-2", createdAt.plusSeconds(35), Duration.ofMinutes(2));
        task.fail("OVERLOAD", "provider overloaded", createdAt.plusSeconds(40), retryDelay);
        assertThat(task.isDispatchable(createdAt.plusSeconds(99))).isFalse();
        assertThat(task.isDispatchable(createdAt.plusSeconds(100))).isTrue();
        task.claim("worker-3", createdAt.plusSeconds(100), Duration.ofMinutes(2));
        task.fail("OVERLOAD", "provider overloaded", createdAt.plusSeconds(105), retryDelay);

        // 达到最大尝试次数后保留失败分类和完成时间，不再被异步执行器领取。
        assertThat(task.getStatus()).isEqualTo(SummaryTaskStatus.FAILED);
        assertThat(task.getAttemptCount()).isEqualTo(3);
        assertThat(task.getFinishedAt()).isEqualTo(createdAt.plusSeconds(105));
        assertThat(task.isDispatchable(createdAt.plusSeconds(1000))).isFalse();
    }
}
