package org.opengoofy.index12306.ai.agentservice.infra.model.routing;

import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelCircuitState;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelFailureCategory;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.infra.config.AgentModelProperties;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelRole;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型组合级熔断和半开恢复测试。
 */
class ModelHealthTrackerTests {

    /**
     * 验证连续失败打开熔断器，冷却后仅允许单个半开探测并可由成功关闭。
     */
    @Test
    void circuitOpensAndRecoversThroughSingleProbe() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-15T00:00:00Z"));
        ModelHealthTracker tracker = new ModelHealthTracker(properties(), clock);

        // 连续两次超时达到阈值后，候选模型在冷却期内不再接收请求。
        tracker.recordFailure(ModelRole.ANSWER_TOOL, "primary", ModelFailureCategory.TIMEOUT);
        tracker.recordFailure(ModelRole.ANSWER_TOOL, "primary", ModelFailureCategory.TIMEOUT);
        assertThat(tracker.snapshot(ModelRole.ANSWER_TOOL, "primary").state())
                .isEqualTo(ModelCircuitState.OPEN);
        assertThat(tracker.tryAcquire(ModelRole.ANSWER_TOOL, "primary")).isFalse();

        // 冷却结束后只有首个请求获得半开探测资格，探测成功后恢复关闭状态。
        clock.advance(Duration.ofSeconds(10));
        assertThat(tracker.tryAcquire(ModelRole.ANSWER_TOOL, "primary")).isTrue();
        assertThat(tracker.tryAcquire(ModelRole.ANSWER_TOOL, "primary")).isFalse();
        tracker.recordSuccess(ModelRole.ANSWER_TOOL, "primary");
        assertThat(tracker.snapshot(ModelRole.ANSWER_TOOL, "primary").state())
                .isEqualTo(ModelCircuitState.CLOSED);
        assertThat(tracker.tryAcquire(ModelRole.ANSWER_TOOL, "primary")).isTrue();
    }

    /**
     * 创建用于熔断测试的最小路由配置。
     *
     * @return 失败阈值为二、冷却时间为十秒的配置
     */
    private AgentModelProperties properties() {
        // 本测试只使用全局熔断参数，平台、候选项和路由可保持为空。
        return new AgentModelProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofMillis(10),
                2,
                Duration.ofSeconds(10),
                20,
                Map.of(),
                Map.of(),
                Map.of());
    }

    /**
     * 可由测试显式推进时间的时钟。
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 返回测试统一使用的 UTC 时区。
         *
         * @return UTC 时区
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * 返回使用目标时区但共享当前测试时间的时钟。
         *
         * @param zone 目标时区
         * @return 当前测试时钟
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * 返回当前可控测试时间。
         *
         * @return 当前时间
         */
        @Override
        public Instant instant() {
            return instant;
        }

        /**
         * 将测试时间向前推进指定时长。
         *
         * @param duration 推进时长
         */
        private void advance(Duration duration) {
            // 直接调整内部时间，避免测试依赖真实休眠。
            instant = instant.plus(duration);
        }
    }
}
