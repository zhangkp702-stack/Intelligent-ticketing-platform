package org.opengoofy.index12306.ai.agentservice.chat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.EventType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
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
     * 将单调时钟差转换为非负持续时间。
     *
     * @param startedNanos 开始时间
     * @return 非负耗时
     */
    private Duration elapsed(long startedNanos) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos));
    }
}
