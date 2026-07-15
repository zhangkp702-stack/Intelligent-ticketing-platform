package org.opengoofy.index12306.ai.agentservice.model.routing;

import org.opengoofy.index12306.ai.agentservice.model.config.AgentModelProperties;
import org.opengoofy.index12306.ai.agentservice.model.config.ModelRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维护 provider、model 与 role 组合级别的熔断和恢复状态。
 */
@Component
public class ModelHealthTracker {

    private final AgentModelProperties properties;
    private final Clock clock;
    private final Map<CircuitKey, CircuitEntry> circuits = new ConcurrentHashMap<>();

    /**
     * 使用系统 UTC 时间创建模型健康跟踪器。
     *
     * @param properties 熔断阈值和冷却时间配置
     */
    @Autowired
    public ModelHealthTracker(AgentModelProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ModelHealthTracker(AgentModelProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 判断指定角色和候选模型当前是否允许发起调用，并独占半开探测机会。
     *
     * @param role 模型角色
     * @param candidateId 候选模型标识
     * @return 允许调用时返回 {@code true}
     */
    public boolean tryAcquire(ModelRole role, String candidateId) {
        CircuitEntry entry = circuits.computeIfAbsent(new CircuitKey(role, candidateId), ignored -> new CircuitEntry());
        return entry.tryAcquire(clock.instant());
    }

    /**
     * 记录成功调用并关闭熔断器、清空连续失败计数。
     *
     * @param role 模型角色
     * @param candidateId 候选模型标识
     */
    public void recordSuccess(ModelRole role, String candidateId) {
        CircuitEntry entry = circuits.computeIfAbsent(new CircuitKey(role, candidateId), ignored -> new CircuitEntry());
        entry.recordSuccess(clock.instant());
    }

    /**
     * 根据故障分类累计失败并在达到阈值时打开熔断器。
     *
     * @param role 模型角色
     * @param candidateId 候选模型标识
     * @param category 故障分类
     */
    public void recordFailure(ModelRole role, String candidateId, ModelFailureCategory category) {
        CircuitEntry entry = circuits.computeIfAbsent(new CircuitKey(role, candidateId), ignored -> new CircuitEntry());
        entry.recordFailure(clock.instant(), category, properties.failureThreshold(), properties.openDuration());
    }

    /**
     * 释放未实际调用的半开探测资格，例如平台并发已满时使用。
     *
     * @param role 模型角色
     * @param candidateId 候选模型标识
     */
    public void releaseProbe(ModelRole role, String candidateId) {
        CircuitEntry entry = circuits.get(new CircuitKey(role, candidateId));
        if (entry != null) {
            entry.releaseProbe();
        }
    }

    /**
     * 返回当前全部角色与候选模型组合的不可变健康快照。
     *
     * @return 以“角色:候选项”为键的健康状态
     */
    public Map<String, CircuitSnapshot> snapshots() {
        Map<String, CircuitSnapshot> snapshots = new LinkedHashMap<>();
        circuits.forEach((key, entry) -> snapshots.put(key.toString(), entry.snapshot()));
        return Map.copyOf(snapshots);
    }

    /**
     * 返回指定组合的当前健康快照，尚未调用过的组合按关闭状态返回。
     *
     * @param role 模型角色
     * @param candidateId 候选模型标识
     * @return 当前健康快照
     */
    public CircuitSnapshot snapshot(ModelRole role, String candidateId) {
        CircuitEntry entry = circuits.get(new CircuitKey(role, candidateId));
        return entry == null ? CircuitSnapshot.initial() : entry.snapshot();
    }

    private record CircuitKey(ModelRole role, String candidateId) {

        /**
         * 返回稳定且适合观测输出的组合键。
         *
         * @return 角色与候选项组合字符串
         */
        @Override
        public String toString() {
            return role + ":" + candidateId;
        }
    }

    /**
     * 熔断器对外只读快照。
     *
     * @param state 当前熔断状态
     * @param consecutiveFailures 连续失败次数
     * @param openUntil 熔断冷却结束时间
     * @param lastSuccessAt 最近成功时间
     * @param lastFailureAt 最近失败时间
     * @param lastFailureCategory 最近失败分类
     */
    public record CircuitSnapshot(
            ModelCircuitState state,
            int consecutiveFailures,
            Instant openUntil,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            ModelFailureCategory lastFailureCategory) {

        /**
         * 创建尚未发生调用的初始健康快照。
         *
         * @return 关闭状态且没有历史时间的快照
         */
        public static CircuitSnapshot initial() {
            return new CircuitSnapshot(ModelCircuitState.CLOSED, 0, null, null, null, null);
        }
    }

    private static final class CircuitEntry {

        private ModelCircuitState state = ModelCircuitState.CLOSED;
        private int consecutiveFailures;
        private Instant openUntil;
        private Instant lastSuccessAt;
        private Instant lastFailureAt;
        private ModelFailureCategory lastFailureCategory;
        private boolean probeInFlight;

        /**
         * 根据当前时间判断是否允许调用，并保证半开状态只有一个探测请求。
         *
         * @param now 当前时间
         * @return 允许调用时返回 {@code true}
         */
        private synchronized boolean tryAcquire(Instant now) {
            if (state == ModelCircuitState.CLOSED) {
                return true;
            }
            if (state == ModelCircuitState.OPEN && openUntil != null && !now.isBefore(openUntil)) {
                state = ModelCircuitState.HALF_OPEN;
                probeInFlight = false;
            }
            if (state == ModelCircuitState.HALF_OPEN && !probeInFlight) {
                probeInFlight = true;
                return true;
            }
            return false;
        }

        /**
         * 记录成功结果并恢复为关闭状态。
         *
         * @param now 成功时间
         */
        private synchronized void recordSuccess(Instant now) {
            state = ModelCircuitState.CLOSED;
            consecutiveFailures = 0;
            openUntil = null;
            lastSuccessAt = now;
            lastFailureCategory = null;
            probeInFlight = false;
        }

        /**
         * 记录失败并根据分类、阈值和半开状态决定是否重新熔断。
         *
         * @param now 失败时间
         * @param category 故障分类
         * @param threshold 连续失败阈值
         * @param openDuration 冷却时间
         */
        private synchronized void recordFailure(
                Instant now,
                ModelFailureCategory category,
                int threshold,
                java.time.Duration openDuration) {
            lastFailureAt = now;
            lastFailureCategory = category;

            // 不影响健康度的输入或容量错误只释放探测资格，不污染熔断计数。
            if (!category.countsTowardCircuit()) {
                probeInFlight = false;
                return;
            }
            consecutiveFailures++;
            boolean shouldOpen = category.opensImmediately()
                    || consecutiveFailures >= threshold
                    || state == ModelCircuitState.HALF_OPEN;
            if (shouldOpen) {
                state = ModelCircuitState.OPEN;
                openUntil = now.plus(openDuration);
            }
            probeInFlight = false;
        }

        /**
         * 释放半开探测资格但不改变健康判断。
         */
        private synchronized void releaseProbe() {
            probeInFlight = false;
        }

        /**
         * 复制当前可变状态为不可变快照。
         *
         * @return 当前健康快照
         */
        private synchronized CircuitSnapshot snapshot() {
            return new CircuitSnapshot(
                    state,
                    consecutiveFailures,
                    openUntil,
                    lastSuccessAt,
                    lastFailureAt,
                    lastFailureCategory);
        }
    }
}
