package org.opengoofy.index12306.ai.agentservice.chat.service;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.EventType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 记录在线对话首事件、首个文本增量和整轮响应耗时，不使用请求标识等高基数标签。
 */
@Component
public class AgentChatMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 创建在线对话指标记录器。
     *
     * @param meterRegistry 应用指标注册表
     */
    public AgentChatMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 为单次对话事件流记录首事件、首个文本增量、终态和总耗时。
     *
     * @param source 原始对话事件流
     * @return 保持原事件和异常语义不变的观测事件流
     */
    public Flux<ChatEvent> observe(Flux<ChatEvent> source) {
        return Flux.defer(() -> {
            long startedNanos = System.nanoTime();
            AtomicBoolean firstEventRecorded = new AtomicBoolean();
            AtomicBoolean firstTokenRecorded = new AtomicBoolean();
            AtomicBoolean terminalRecorded = new AtomicBoolean();
            AtomicReference<String> outcome = new AtomicReference<>("INCOMPLETE");
            AtomicReference<String> reused = new AtomicReference<>("false");

            // 首事件反映服务端开始产生 SSE 数据的时间，首个 DELTA 反映用户看到回答正文的时间。
            return source
                    .doOnNext(event -> {
                        if (firstEventRecorded.compareAndSet(false, true)) {
                            recordTimer("agent.chat.time.to.first.event", startedNanos);
                        }
                        if (event.type() == EventType.DELTA
                                && event.delta() != null
                                && !event.delta().isBlank()
                                && firstTokenRecorded.compareAndSet(false, true)) {
                            recordTimer("agent.chat.time.to.first.token", startedNanos);
                        }
                        if (event.type() == EventType.DONE) {
                            outcome.set("SUCCESS");
                            reused.set(Boolean.toString(event.reused()));
                        } else if (event.type() == EventType.ERROR) {
                            outcome.set("ERROR");
                        }
                    })
                    .doOnError(ignored -> outcome.set("ERROR"))
                    .doOnCancel(() -> outcome.set("CANCELLED"))
                    .doFinally(signal -> recordTerminal(
                            startedNanos, signal, outcome.get(), reused.get(), terminalRecorded));
        });
    }

    /**
     * 记录会话上下文加载阶段耗时。
     *
     * @param startedNanos 阶段开始的单调时钟值
     * @param outcome 阶段结果
     */
    public void recordContextLoad(long startedNanos, String outcome) {
        // 上下文阶段只使用成功或失败标签，避免会话标识进入指标系统。
        recordTimer("agent.chat.context.duration", startedNanos, "outcome", outcome);
    }

    /**
     * 记录问题改写阶段耗时和触发结果。
     *
     * @param startedNanos 阶段开始的单调时钟值
     * @param modelInvoked 是否调用改写模型
     * @param rewritten 问题正文是否实际发生变化
     */
    public void recordRewrite(
            long startedNanos,
            boolean modelInvoked,
            boolean rewritten) {
        // 触发率和实际改写率使用布尔标签聚合，不记录问题正文。
        Timer.builder("agent.chat.rewrite.duration")
                .tags(
                        "modelInvoked", Boolean.toString(modelInvoked),
                        "rewritten", Boolean.toString(rewritten))
                .register(meterRegistry)
                .record(elapsed(startedNanos));
        meterRegistry.counter(
                "agent.chat.rewrite.requests",
                "modelInvoked", Boolean.toString(modelInvoked),
                "rewritten", Boolean.toString(rewritten)).increment();
    }

    /**
     * 记录问题分流耗时、路径、工具可用状态和命中业务组。
     *
     * @param startedNanos 阶段开始的单调时钟值
     * @param route 普通问答或工具辅助路径
     * @param toolAvailability 工具无需加载、可用或缺失
     * @param businessGroups 命中的低基数业务组
     */
    public void recordRouting(
            long startedNanos,
            String route,
            String toolAvailability,
            Set<String> businessGroups) {
        // 路径和工具状态用于直接比较普通问答与 MCP 业务流量。
        Timer.builder("agent.chat.routing.duration")
                .tags("route", route, "toolAvailability", toolAvailability)
                .register(meterRegistry)
                .record(elapsed(startedNanos));
        meterRegistry.counter(
                "agent.chat.routing.requests",
                "route", route,
                "toolAvailability", toolAvailability).increment();
        for (String group : businessGroups) {
            // 每个业务组单独计数，避免把任意组合拼成高基数标签。
            meterRegistry.counter("agent.chat.routing.groups", "group", group).increment();
        }
    }

    /**
     * 记录本轮分流要求但未注册的安全工具。
     *
     * @param missingToolNames 缺失工具名称
     */
    public void recordMissingTools(Set<String> missingToolNames) {
        for (String toolName : missingToolNames) {
            // 工具名称来自固定白名单，属于可控的低基数标签。
            meterRegistry.counter("agent.chat.tools.missing", "tool", toolName).increment();
        }
    }

    /**
     * 为回答模型的完整响应流记录耗时和终态。
     *
     * @param source 回答模型响应流
     * @param toolsEnabled 是否向回答模型注册了工具
     * @param <T> 模型响应块类型
     * @return 保持原响应和异常语义不变的观测流
     */
    public <T> Flux<T> observeModel(Flux<T> source, boolean toolsEnabled) {
        return Flux.defer(() -> {
            long startedNanos = System.nanoTime();
            AtomicReference<String> outcome = new AtomicReference<>("SUCCESS");

            // 错误和取消分别统计，正常完成保持 SUCCESS。
            return source
                    .doOnError(ignored -> outcome.set("ERROR"))
                    .doOnCancel(() -> outcome.set("CANCELLED"))
                    .doFinally(signal -> {
                        String actualOutcome = signal == SignalType.CANCEL
                                ? "CANCELLED"
                                : signal == SignalType.ON_ERROR ? "ERROR" : outcome.get();
                        Timer.builder("agent.chat.model.duration")
                                .tags(
                                        "outcome", actualOutcome,
                                        "toolsEnabled", Boolean.toString(toolsEnabled))
                                .register(meterRegistry)
                                .record(elapsed(startedNanos));
                    });
        });
    }

    /**
     * 记录模型完成后的业务收口和轮次持久化耗时。
     *
     * @param startedNanos 阶段开始的单调时钟值
     * @param outcome 阶段结果
     */
    public void recordCompletion(long startedNanos, String outcome) {
        // 完成阶段覆盖草案读取、权威正文校正和轮次终态持久化。
        recordTimer("agent.chat.completion.duration", startedNanos, "outcome", outcome);
    }

    /**
     * 记录整轮对话的最终结果和总耗时，确保取消、异常和正常结束只记录一次。
     *
     * @param startedNanos 对话订阅开始时间
     * @param signal Reactor 最终信号
     * @param observedOutcome 事件流已经识别出的结果
     * @param reused 是否复用了既有回答
     * @param terminalRecorded 终态幂等标记
     */
    private void recordTerminal(
            long startedNanos,
            SignalType signal,
            String observedOutcome,
            String reused,
            AtomicBoolean terminalRecorded) {
        if (!terminalRecorded.compareAndSet(false, true)) {
            return;
        }

        // 没有 DONE 的正常结束单独标记为 INCOMPLETE，避免把协议缺陷统计成成功请求。
        String actualOutcome = signal == SignalType.CANCEL
                ? "CANCELLED"
                : signal == SignalType.ON_ERROR ? "ERROR" : observedOutcome;
        meterRegistry.counter(
                "agent.chat.requests",
                "outcome", actualOutcome,
                "reused", reused).increment();
        Timer.builder("agent.chat.duration")
                .tags("outcome", actualOutcome, "reused", reused)
                .register(meterRegistry)
                .record(elapsed(startedNanos));
    }

    /**
     * 记录从对话订阅开始到指定里程碑的耗时。
     *
     * @param metricName 低基数计时器名称
     * @param startedNanos 对话订阅开始时间
     */
    private void recordTimer(String metricName, long startedNanos) {
        Timer.builder(metricName)
                .register(meterRegistry)
                .record(elapsed(startedNanos));
    }

    /**
     * 使用指定低基数标签记录阶段耗时。
     *
     * @param metricName 指标名称
     * @param startedNanos 阶段开始的单调时钟值
     * @param tags 成对出现的标签名称和值
     */
    private void recordTimer(String metricName, long startedNanos, String... tags) {
        Timer.builder(metricName)
                .tags(tags)
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
}
