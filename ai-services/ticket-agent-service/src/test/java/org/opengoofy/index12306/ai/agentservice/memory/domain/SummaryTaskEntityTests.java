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
                "conversation", 2, 0, 3, createdAt);

        // 首次领取失败后进入等待状态，重试时间到达前不可再次分发。
        task.published("message-1", createdAt);
        assertThat(task.claim(1, "worker-1", createdAt, Duration.ofMinutes(2))).isTrue();
        assertThat(task.fail("TIMEOUT", "line one\nline two", createdAt.plusSeconds(5), retryDelay)).isTrue();
        assertThat(task.getStatus()).isEqualTo(SummaryTaskStatus.RETRY_WAIT);
        assertThat(task.getFailureMessage()).isEqualTo("line one line two");

        // 第二次失败等待时间按尝试次数增长，第三次失败后进入不可恢复状态。
        assertThat(task.claim(1, "worker-2", createdAt.plusSeconds(35), Duration.ofMinutes(2))).isTrue();
        assertThat(task.fail("OVERLOAD", "provider overloaded", createdAt.plusSeconds(40), retryDelay)).isTrue();
        assertThat(task.claim(1, "worker-3", createdAt.plusSeconds(100), Duration.ofMinutes(2))).isTrue();
        assertThat(task.fail("OVERLOAD", "provider overloaded", createdAt.plusSeconds(105), retryDelay)).isFalse();

        // 达到最大尝试次数后保留失败分类和完成时间，不再被异步执行器领取。
        assertThat(task.getStatus()).isEqualTo(SummaryTaskStatus.FAILED);
        assertThat(task.getAttemptCount()).isEqualTo(3);
        assertThat(task.getFinishedAt()).isEqualTo(createdAt.plusSeconds(105));
        assertThat(task.claim(1, "worker-4", createdAt.plusSeconds(1000), Duration.ofMinutes(2))).isFalse();
    }

    /**
     * 验证模型执行期间到达的新消息只推进目标边界，并在完成后复用任务行发布下一版本。
     */
    @Test
    void coalescesMessagesArrivingWhileTaskIsRunning() {
        Instant createdAt = Instant.parse("2026-07-15T00:00:00Z");
        SummaryTaskEntity task = SummaryTaskEntity.pending("conversation", 2, 0, 3, createdAt);

        task.published("message-1", createdAt);
        assertThat(task.claim(1, "worker-1", createdAt, Duration.ofMinutes(2))).isTrue();
        task.request(4, 0, createdAt.plusSeconds(1));
        task.succeed(1, createdAt.plusSeconds(2));

        assertThat(task.getStatus()).isEqualTo(SummaryTaskStatus.PENDING);
        assertThat(task.getDesiredThroughSequence()).isEqualTo(4);
        assertThat(task.getEventVersion()).isEqualTo(2);
        assertThat(task.getExpectedSummaryVersion()).isEqualTo(1);
    }

    /**
     * 验证消费者租约到期后任务可以重新发布，并保留已消耗的尝试次数。
     */
    @Test
    void recoversExpiredConsumerLeaseWithoutResettingAttempts() {
        Instant createdAt = Instant.parse("2026-07-15T00:00:00Z");
        SummaryTaskEntity task = SummaryTaskEntity.pending("conversation", 2, 0, 3, createdAt);

        task.published("message-1", createdAt);
        assertThat(task.claim(1, "worker-1", createdAt, Duration.ofSeconds(30))).isTrue();
        assertThat(task.recoverForRepublish(createdAt.plusSeconds(29))).isFalse();
        assertThat(task.recoverForRepublish(createdAt.plusSeconds(30))).isTrue();

        assertThat(task.getStatus()).isEqualTo(SummaryTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getEventVersion()).isEqualTo(1);
    }
}
