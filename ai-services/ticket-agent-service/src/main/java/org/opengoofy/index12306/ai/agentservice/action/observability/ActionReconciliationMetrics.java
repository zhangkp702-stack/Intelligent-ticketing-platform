package org.opengoofy.index12306.ai.agentservice.action.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记录动作对账的发布、消费、权威查询和积压状态，所有标签均为固定低基数枚举。
 */
@Component
public class ActionReconciliationMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxPublishing = new AtomicLong();
    private final AtomicLong inboxRetryWaiting = new AtomicLong();
    private final AtomicLong inboxFailed = new AtomicLong();
    private final AtomicLong commandUnknown = new AtomicLong();
    private final AtomicLong commandManualReview = new AtomicLong();

    /**
     * 创建动作对账指标记录器，并一次性注册稳定状态的积压 Gauge。
     *
     * @param meterRegistry 应用指标注册表
     */
    public ActionReconciliationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerBacklogGauge("outbox", "PENDING", outboxPending);
        registerBacklogGauge("outbox", "PUBLISHING", outboxPublishing);
        registerBacklogGauge("inbox", "RETRY_WAIT", inboxRetryWaiting);
        registerBacklogGauge("inbox", "FAILED", inboxFailed);
        registerBacklogGauge("command", "UNKNOWN", commandUnknown);
        registerBacklogGauge("command", "MANUAL_REVIEW", commandManualReview);
    }

    /**
     * 记录一次 Outbox 向消息代理的同步发布尝试及耗时。
     *
     * @param outcome 发布结果
     * @param startedNanos 发送前取得的单调时钟值
     */
    public void recordOutboxPublication(PublicationOutcome outcome, long startedNanos) {
        // 发布结果只按成功和失败聚合，不把 topic、eventId 或异常类型作为指标标签。
        meterRegistry.counter("agent.action.reconciliation.outbox.publications", "outcome", outcome.name()).increment();
        Timer.builder("agent.action.reconciliation.outbox.publication.duration")
                .tag("outcome", outcome.name())
                .register(meterRegistry)
                .record(elapsed(startedNanos));
    }

    /**
     * 记录消息契约校验和 Inbox 领取的稳定结果。
     *
     * @param outcome 消息领取结果
     */
    public void recordInboxClaim(InboxClaimOutcome outcome) {
        // 重复、过期和契约不匹配均由固定枚举区分，避免业务标识造成标签爆炸。
        meterRegistry.counter("agent.action.reconciliation.inbox.claims", "outcome", outcome.name()).increment();
    }

    /**
     * 记录一次下游权威状态查询返回的稳定状态和耗时。
     *
     * @param actionType 高风险操作类型
     * @param outcome 权威查询返回状态
     * @param startedNanos 查询前取得的单调时钟值
     */
    public void recordProbe(AgentActionType actionType, ProbeOutcome outcome, long startedNanos) {
        // actionType 和返回状态都是领域枚举，适合用于对账成功率和延迟告警。
        meterRegistry.counter(
                "agent.action.reconciliation.probes",
                "actionType", actionType.name(),
                "outcome", outcome.name()).increment();
        Timer.builder("agent.action.reconciliation.probe.duration")
                .tags("actionType", actionType.name(), "outcome", outcome.name())
                .register(meterRegistry)
                .record(elapsed(startedNanos));
    }

    /**
     * 记录一次自动对账最终转入人工复核，或由人工重新开启只读对账。
     *
     * @param actionType 高风险操作类型
     * @param outcome 人工复核状态变化
     */
    public void recordManualReview(AgentActionType actionType, ManualReviewOutcome outcome) {
        // 不记录操作员或人工原因；完整审计仍保留在可靠命令审计表中。
        meterRegistry.counter(
                "agent.action.reconciliation.manual_reviews",
                "actionType", actionType.name(),
                "outcome", outcome.name()).increment();
    }

    /**
     * 累加自动恢复的发布或消费租约数量。
     *
     * @param recoveredCount 本轮成功恢复数量
     */
    public void recordRecovery(int recoveredCount) {
        if (recoveredCount > 0) {
            // 使用增量而非单次布尔值，方便观察实例故障后实际被接管的工作量。
            meterRegistry.counter("agent.action.reconciliation.recoveries").increment(recoveredCount);
        }
    }

    /**
     * 用一次数据库快照刷新稳定状态积压 Gauge。
     *
     * @param outboxPendingCount 待发布 Outbox 数量
     * @param outboxPublishingCount 发布中的 Outbox 数量
     * @param inboxRetryWaitingCount 等待重试的 Inbox 数量
     * @param inboxFailedCount 已耗尽重试的 Inbox 数量
     * @param commandUnknownCount 等待权威对账的命令数量
     * @param commandManualReviewCount 等待人工复核的命令数量
     */
    public void refreshBacklog(
            long outboxPendingCount,
            long outboxPublishingCount,
            long inboxRetryWaitingCount,
            long inboxFailedCount,
            long commandUnknownCount,
            long commandManualReviewCount) {
        // 每项指标只保存最近一次完整数据库快照，绝不将动作、用户或事件标识放入 Gauge 标签。
        outboxPending.set(nonNegative(outboxPendingCount));
        outboxPublishing.set(nonNegative(outboxPublishingCount));
        inboxRetryWaiting.set(nonNegative(inboxRetryWaitingCount));
        inboxFailed.set(nonNegative(inboxFailedCount));
        commandUnknown.set(nonNegative(commandUnknownCount));
        commandManualReview.set(nonNegative(commandManualReviewCount));
    }

    /**
     * 记录一次积压快照读取失败。
     */
    public void recordBacklogRefreshFailure() {
        // 数据库不可读时保留上一轮快照，并单独计数，避免把旧值误判为实时值。
        meterRegistry.counter("agent.action.reconciliation.backlog.refresh.failures").increment();
    }

    /**
     * 注册一条固定标签的积压 Gauge。
     *
     * @param resource 可靠资源类别
     * @param status 稳定状态
     * @param value 最新数量
     */
    private void registerBacklogGauge(String resource, String status, AtomicLong value) {
        Gauge.builder("agent.action.reconciliation.backlog", value, AtomicLong::doubleValue)
                .tags("resource", resource, "status", status)
                .register(meterRegistry);
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
     * 防御性约束 Gauge，避免错误调用把业务积压暴露为负数。
     *
     * @param value 原始数量
     * @return 非负数量
     */
    private long nonNegative(long value) {
        return Math.max(0L, value);
    }

    /** 发布 Outbox 的稳定结果。 */
    public enum PublicationOutcome {
        /** 消息代理已确认且本地 Outbox 已写入发布确认。 */
        SUCCEEDED,
        /** 发送或本地发布确认失败。 */
        FAILED
    }

    /** Inbox 领取的稳定结果。 */
    public enum InboxClaimOutcome {
        /** 消息已通过契约校验并取得消费租约。 */
        CLAIMED,
        /** 消息重复、版本过期或当前没有可领取租约。 */
        IGNORED,
        /** 消息字段与本地 Outbox 权威快照不一致。 */
        CONTRACT_REJECTED
    }

    /** 权威状态查询的稳定结果。 */
    public enum ProbeOutcome {
        /** 下游明确返回业务成功。 */
        SUCCEEDED,
        /** 下游明确返回业务失败。 */
        FAILED,
        /** 下游仍在处理，后续只读查询将继续。 */
        PROCESSING,
        /** 查询端口抛出异常，没有得到权威结论。 */
        EXCEPTION
    }

    /** 人工复核生命周期中的稳定状态变化。 */
    public enum ManualReviewOutcome {
        /** 自动重试耗尽后进入人工复核。 */
        ENTERED,
        /** 受权操作员重新安排只读对账。 */
        RESUMED
    }
}
