package org.opengoofy.index12306.ai.agentservice.model.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opengoofy.index12306.ai.agentservice.model.config.AgentModelProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelAttemptRecorder.class);

    private final MeterRegistry meterRegistry;
    private final int capacity;
    private final ModelAttemptAuditWriter auditWriter;
    private final Deque<ModelAttemptEvent> recentEvents = new ArrayDeque<>();

    /**
     * 创建模型尝试记录器。
     *
     * @param meterRegistry Micrometer 指标注册表
     * @param properties 模型路由配置
     */
    public ModelAttemptRecorder(MeterRegistry meterRegistry, AgentModelProperties properties) {
        this(meterRegistry, properties, (ModelAttemptAuditWriter) null);
    }

    /**
     * 创建带可选持久化写入器的模型尝试记录器。
     *
     * @param meterRegistry Micrometer 指标注册表
     * @param properties 模型路由配置
     * @param auditWriterProvider 持久化审计写入器提供方
     */
    @Autowired
    public ModelAttemptRecorder(
            MeterRegistry meterRegistry,
            AgentModelProperties properties,
            ObjectProvider<ModelAttemptAuditWriter> auditWriterProvider) {
        this(meterRegistry, properties, auditWriterProvider.getIfAvailable());
    }

    /**
     * 初始化模型尝试记录器并绑定可选持久化写入器。
     *
     * @param meterRegistry Micrometer 指标注册表
     * @param properties 模型路由配置
     * @param auditWriter 持久化审计写入器，可以为空
     */
    private ModelAttemptRecorder(
            MeterRegistry meterRegistry,
            AgentModelProperties properties,
            ModelAttemptAuditWriter auditWriter) {
        this.meterRegistry = meterRegistry;
        this.capacity = properties.auditCapacity();
        this.auditWriter = auditWriter;
    }

    /**
     * 记录一次候选模型尝试，并同步更新次数和延迟指标。
     *
     * @param event 不含敏感正文的模型尝试事件
     * @return 持久化审计标识，未启用或写入失败时为空
     */
    public String record(ModelAttemptEvent event) {
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
        LOGGER.info(
                "Agent模型路由尝试汇总，role={}, provider={}, candidate={}, outcome={}, category={}, aggregateDurationMs={}",
                event.role(), event.providerId(), event.candidateId(), event.outcome(), category,
                event.durationMillis());

        // 仅在内存中保存固定数量的最近事件，阶段 3 再替换为持久化审计表。
        synchronized (recentEvents) {
            recentEvents.addFirst(event);
            while (recentEvents.size() > capacity) {
                recentEvents.removeLast();
            }
        }

        // 审计写入失败不得改变模型调用结果，避免观测系统反向阻断用户请求。
        if (auditWriter == null) {
            return null;
        }
        try {
            return auditWriter.record(event);
        } catch (RuntimeException ex) {
            meterRegistry.counter("agent.model.audit.persistence.failures").increment();
            LOGGER.warn("模型调用审计持久化失败，候选模型: {}", event.candidateId(), ex);
            return null;
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
