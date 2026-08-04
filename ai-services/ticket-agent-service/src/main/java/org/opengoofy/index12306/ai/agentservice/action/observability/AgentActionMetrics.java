package org.opengoofy.index12306.ai.agentservice.action.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 记录高风险智能体操作的确认拒绝、执行终态和耗时，不使用请求或操作标识等高基数标签。
 */
@Component
public class AgentActionMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 创建高风险操作指标记录器。
     *
     * @param meterRegistry 应用指标注册表
     */
    public AgentActionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录确认在真实写操作开始前被拒绝的稳定原因。
     *
     * @param actionType 操作类型
     * @param reason 低基数拒绝原因
     */
    public void recordConfirmationRejected(
            AgentActionType actionType,
            ConfirmationRejection reason) {
        // 只使用枚举值作为标签，避免 actionId、requestId 或异常正文进入指标系统。
        meterRegistry.counter(
                "agent.action.confirmation.rejections",
                "actionType", actionType.name(),
                "reason", reason.name()).increment();
    }

    /**
     * 记录一份待确认草案首次进入过期终态。
     *
     * @param actionType 操作类型
     */
    public void recordConfirmationExpired(AgentActionType actionType) {
        // 过期终态按草案类型计数，可用于评估确认时限是否需要调整。
        meterRegistry.counter(
                "agent.action.confirmation.expirations",
                "actionType", actionType.name()).increment();
    }

    /**
     * 记录一次已经发起真实业务写调用的最终状态和执行耗时。
     *
     * @param actionType 操作类型
     * @param outcome 成功、明确失败或结果未知终态
     * @param startedNanos 真实写调用开始的单调时钟值
     */
    public void recordExecution(
            AgentActionType actionType,
            AgentActionStatus outcome,
            long startedNanos) {
        if (outcome != AgentActionStatus.SUCCEEDED
                && outcome != AgentActionStatus.FAILED
                && outcome != AgentActionStatus.UNKNOWN) {
            throw new IllegalArgumentException("操作执行指标只接受最终执行状态");
        }

        // 计数器用于计算成功率和未知结果比例，计时器用于观察下游写链路耗时。
        String outcomeName = outcome.name();
        meterRegistry.counter(
                "agent.action.executions",
                "actionType", actionType.name(),
                "outcome", outcomeName).increment();
        Timer.builder("agent.action.execution.duration")
                .tags(
                        "actionType", actionType.name(),
                        "outcome", outcomeName)
                .register(meterRegistry)
                .record(elapsed(startedNanos));
    }

    /**
     * 将单调时钟差转换为非负持续时间。
     *
     * @param startedNanos 开始时间
     * @return 非负耗时
     */
    private Duration elapsed(long startedNanos) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos));
    }

    /**
     * 确认在真实写调用前终止的固定分类。
     */
    public enum ConfirmationRejection {
        /** 确认令牌或草案已经过期。 */
        EXPIRED,
        /** 下游预览结果已变化。 */
        PREVIEW_CHANGED,
        /** 确认令牌无效。 */
        INVALID_TOKEN,
        /** 草案当前状态不允许确认。 */
        NOT_CONFIRMABLE
    }
}
