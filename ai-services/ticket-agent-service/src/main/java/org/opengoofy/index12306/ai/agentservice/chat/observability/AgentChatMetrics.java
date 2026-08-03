package org.opengoofy.index12306.ai.agentservice.chat.observability;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.EventType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
        // 每次订阅重新创建观测状态，避免重试或重复订阅共享计时与终态标记。
        return Flux.defer(() -> observeSubscription(source));
    }

    /**
     * 为一次实际订阅绑定事件、异常、取消和最终信号的指标回调。
     *
     * @param source 原始对话事件流
     * @return 带指标回调的对话事件流
     */
    private Flux<ChatEvent> observeSubscription(Flux<ChatEvent> source) {
        ChatObservationState state = new ChatObservationState();

        // 回调只更新本次订阅的观测状态，不转换原始事件或终止信号。
        Flux<ChatEvent> observedStream = source
                .doOnNext(event -> recordEvent(event, state))
                .doOnError(ignored -> state.outcome.set("ERROR"))
                .doOnCancel(() -> state.outcome.set("CANCELLED"))
                .doFinally(signal -> recordTerminal(signal, state));
        return observedStream;
    }

    /**
     * 根据对话事件记录首事件、首个正文增量和业务终态。
     *
     * @param event 当前对话事件
     * @param state 本次订阅的观测状态
     */
    private void recordEvent(ChatEvent event, ChatObservationState state) {
        // 首事件反映服务端开始产生 SSE 数据的时间。
        if (state.firstEventRecorded.compareAndSet(false, true)) {
            recordTimer("agent.chat.time.to.first.event", state.startedNanos);
        }

        // 首个非空 DELTA 反映用户首次看到回答正文的时间。
        if (event.type() == EventType.DELTA
                && event.delta() != null
                && !event.delta().isBlank()
                && state.firstTokenRecorded.compareAndSet(false, true)) {
            recordTimer("agent.chat.time.to.first.token", state.startedNanos);
        }

        // DONE 和 ERROR 事件提供协议层终态，最终信号仍会在 doFinally 中兜底校正。
        if (event.type() == EventType.DONE) {
            state.outcome.set("SUCCESS");
            state.reused.set(Boolean.toString(event.reused()));
        } else if (event.type() == EventType.ERROR) {
            state.outcome.set("ERROR");
        }
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
        // 每次订阅独立记录模型流终态，避免重复订阅共享计时状态。
        Flux<T> observedStream = Flux.defer(() -> observeModelSubscription(source, toolsEnabled));
        return observedStream;
    }

    /**
     * 为一次模型响应订阅绑定错误、取消和最终耗时指标。
     *
     * @param source 原始模型响应流
     * @param toolsEnabled 是否向回答模型注册了工具
     * @param <T> 模型响应块类型
     * @return 带模型指标回调的响应流
     */
    private <T> Flux<T> observeModelSubscription(Flux<T> source, boolean toolsEnabled) {
        ModelObservationState state = new ModelObservationState();

        // 回调仅维护当前订阅的结果状态，不改变模型响应内容。
        Flux<T> observedStream = source
                .doOnError(ignored -> state.outcome.set("ERROR"))
                .doOnCancel(() -> state.outcome.set("CANCELLED"))
                .doFinally(signal -> recordModelTerminal(signal, toolsEnabled, state));
        return observedStream;
    }

    /**
     * 记录模型响应流的最终结果和总耗时。
     *
     * @param signal Reactor 最终信号
     * @param toolsEnabled 是否向回答模型注册了工具
     * @param state 本次模型订阅的观测状态
     */
    private void recordModelTerminal(
            SignalType signal,
            boolean toolsEnabled,
            ModelObservationState state) {
        // 错误和取消信号覆盖默认成功状态，确保终态标签与真实结束原因一致。
        String actualOutcome = signal == SignalType.CANCEL
                ? "CANCELLED"
                : signal == SignalType.ON_ERROR ? "ERROR" : state.outcome.get();
        Timer.builder("agent.chat.model.duration")
                .tags(
                        "outcome", actualOutcome,
                        "toolsEnabled", Boolean.toString(toolsEnabled))
                .register(meterRegistry)
                .record(elapsed(state.startedNanos));
    }

    /**
     * 为单个固定链任务记录耗时和终态，不使用任务标识、问题正文等高基数字段。
     *
     * @param source 单个任务的结果流
     * @param intent 当前受控意图
     * @return 保持任务结果、异常和取消语义不变的观测流
     */
    public Mono<TaskExecutionResult> observeTask(
            Mono<TaskExecutionResult> source,
            AgentIntent intent) {
        // 每次订阅独立记录任务终态，缓存前的首次执行只会产生一组指标。
        Mono<TaskExecutionResult> observedTask = Mono.defer(() -> observeTaskSubscription(source, intent));
        return observedTask;
    }

    /**
     * 为一次任务订阅绑定业务结果、错误、取消和最终耗时指标。
     *
     * @param source 原始任务结果
     * @param intent 当前受控意图
     * @return 带任务指标回调的结果
     */
    private Mono<TaskExecutionResult> observeTaskSubscription(
            Mono<TaskExecutionResult> source,
            AgentIntent intent) {
        TaskObservationState state = new TaskObservationState();

        // 业务状态直接作为低基数结果标签，异常和上游取消使用独立固定值。
        Mono<TaskExecutionResult> observedTask = source
                .doOnNext(result -> recordTaskResult(result, intent, state))
                .doOnError(ignored -> state.outcome.set("ERROR"))
                .doOnCancel(() -> state.outcome.set("CANCELLED"))
                .doFinally(signal -> recordTaskTerminal(signal, intent, state));
        return observedTask;
    }

    /**
     * 记录任务返回的业务状态，并在结果交给汇总阶段前完成终态指标。
     *
     * @param result 当前任务结果
     * @param intent 当前受控意图
     * @param state 本次任务订阅的观测状态
     */
    private void recordTaskResult(
            TaskExecutionResult result,
            AgentIntent intent,
            TaskObservationState state) {
        // 同一结果信号内先更新状态再记录指标，保证下游立即查询时已经可见。
        state.outcome.set(result.status().name());
        recordTaskTerminal(SignalType.ON_COMPLETE, intent, state);
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
     * @param signal Reactor 最终信号
     * @param state 本次订阅的观测状态
     */
    private void recordTerminal(SignalType signal, ChatObservationState state) {
        if (!state.terminalRecorded.compareAndSet(false, true)) {
            return;
        }

        // 没有 DONE 的正常结束单独标记为 INCOMPLETE，避免把协议缺陷统计成成功请求。
        String actualOutcome = signal == SignalType.CANCEL
                ? "CANCELLED"
                : signal == SignalType.ON_ERROR ? "ERROR" : state.outcome.get();
        meterRegistry.counter(
                "agent.chat.requests",
                "outcome", actualOutcome,
                "reused", state.reused.get()).increment();
        Timer.builder("agent.chat.duration")
                .tags("outcome", actualOutcome, "reused", state.reused.get())
                .register(meterRegistry)
                .record(elapsed(state.startedNanos));
    }

    /**
     * 记录单个任务唯一一次执行终态和耗时。
     *
     * @param signal Reactor 最终信号
     * @param intent 当前受控意图
     * @param state 本次任务订阅的观测状态
     */
    private void recordTaskTerminal(
            SignalType signal,
            AgentIntent intent,
            TaskObservationState state) {
        if (!state.terminalRecorded.compareAndSet(false, true)) {
            return;
        }

        // 信号级错误或取消覆盖业务结果，正常完成使用任务返回的稳定状态。
        String actualOutcome = signal == SignalType.CANCEL
                ? "CANCELLED"
                : signal == SignalType.ON_ERROR ? "ERROR" : state.outcome.get();
        meterRegistry.counter(
                "agent.chat.task.executions",
                "intent", intent.name(),
                "outcome", actualOutcome).increment();
        Timer.builder("agent.chat.task.duration")
                .tags(
                        "intent", intent.name(),
                        "outcome", actualOutcome)
                .register(meterRegistry)
                .record(elapsed(state.startedNanos));
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

    /**
     * 隔离单次对话订阅的计时、里程碑和终态，支持同一事件流被安全地重复订阅。
     */
    private static final class ChatObservationState {

        private final long startedNanos = System.nanoTime();
        private final AtomicBoolean firstEventRecorded = new AtomicBoolean();
        private final AtomicBoolean firstTokenRecorded = new AtomicBoolean();
        private final AtomicBoolean terminalRecorded = new AtomicBoolean();
        private final AtomicReference<String> outcome = new AtomicReference<>("INCOMPLETE");
        private final AtomicReference<String> reused = new AtomicReference<>("false");
    }

    /**
     * 隔离单次模型响应订阅的计时和终态。
     */
    private static final class ModelObservationState {

        private final long startedNanos = System.nanoTime();
        private final AtomicReference<String> outcome = new AtomicReference<>("SUCCESS");
    }

    /**
     * 隔离单次任务订阅的计时、业务结果和终态幂等标记。
     */
    private static final class TaskObservationState {

        private final long startedNanos = System.nanoTime();
        private final AtomicReference<String> outcome = new AtomicReference<>("INCOMPLETE");
        private final AtomicBoolean terminalRecorded = new AtomicBoolean();
    }
}
