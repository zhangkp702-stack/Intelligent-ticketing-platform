package org.opengoofy.index12306.ai.agentservice.model.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opengoofy.index12306.ai.agentservice.model.config.AgentModelProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 保存有限数量的模型调用审计事件，并输出低基数 Micrometer 指标。
 */
@Component
public class ModelAttemptRecorder {

    private final MeterRegistry meterRegistry;
    private final int capacity;
    private final Deque<ModelAttemptEvent> recentEvents = new ArrayDeque<>();

    /**
     * 创建模型尝试记录器。
     *
     * @param meterRegistry Micrometer 指标注册表
     * @param properties 模型路由配置
     */
    public ModelAttemptRecorder(MeterRegistry meterRegistry, AgentModelProperties properties) {
        this.meterRegistry = meterRegistry;
        this.capacity = properties.auditCapacity();
    }

    /**
     * 记录一次候选模型尝试，并同步更新次数和延迟指标。
     *
     * @param event 不含敏感正文的模型尝试事件
     */
    public void record(ModelAttemptEvent event) {
        // 指标标签只使用固定配置项和枚举，避免异常正文造成高基数或敏感信息泄露。
        String category = event.failureCategory() == null ? "NONE" : event.failureCategory().name();
        meterRegistry.counter(
                "agent.model.attempts",
                "role", event.role().name(),
                "provider", event.providerId(),
                "candidate", event.candidateId(),
                "outcome", event.outcome().name(),
                "category", category).increment();
        Timer.builder("agent.model.attempt.duration")
                .tags(
                        "role", event.role().name(),
                        "provider", event.providerId(),
                        "candidate", event.candidateId(),
                        "outcome", event.outcome().name())
                .register(meterRegistry)
                .record(Duration.ofMillis(event.durationMillis()));

        // 仅在内存中保存固定数量的最近事件，阶段 3 再替换为持久化审计表。
        synchronized (recentEvents) {
            recentEvents.addFirst(event);
            while (recentEvents.size() > capacity) {
                recentEvents.removeLast();
            }
        }
    }

    /**
     * 返回按时间倒序排列的最近模型尝试事件副本。
     *
     * @return 不可修改的审计事件列表
     */
    public List<ModelAttemptEvent> recent() {
        synchronized (recentEvents) {
            return List.copyOf(new ArrayList<>(recentEvents));
        }
    }
}
