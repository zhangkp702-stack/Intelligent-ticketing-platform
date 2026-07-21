package org.opengoofy.index12306.ai.agentservice.infra.model.routing;

import org.opengoofy.index12306.ai.agentservice.infra.config.AgentModelProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 以平台为边界隔离并发，防止一个平台耗尽全部模型调用容量。
 */
@Component
public class ProviderConcurrencyLimiter {

    private final AgentModelProperties properties;
    private final Map<String, Semaphore> semaphores;

    /**
     * 根据各平台并发上限创建公平信号量。
     *
     * @param properties 模型路由配置
     */
    public ProviderConcurrencyLimiter(AgentModelProperties properties) {
        this.properties = properties;
        Map<String, Semaphore> providerSemaphores = new HashMap<>();

        // 每个平台持有独立许可池，平台之间不会相互占用并发额度。
        properties.providers().forEach((providerId, provider) ->
                providerSemaphores.put(providerId, new Semaphore(provider.maxConcurrent(), true)));
        this.semaphores = Map.copyOf(providerSemaphores);
    }

    /**
     * 在限定时间内获取指定平台的并发许可。
     *
     * @param providerId 平台标识
     * @return 获取成功时返回可关闭许可，否则返回空
     */
    public Optional<Permit> acquire(String providerId) {
        Semaphore semaphore = semaphores.get(providerId);
        if (semaphore == null) {
            return Optional.empty();
        }
        try {
            boolean acquired = semaphore.tryAcquire(
                    properties.acquireTimeout().toNanos(), TimeUnit.NANOSECONDS);
            return acquired ? Optional.of(new Permit(semaphore)) : Optional.empty();
        } catch (InterruptedException ex) {
            // 恢复中断标记，让上层请求取消逻辑可以继续感知中断。
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * 可自动释放且防止重复释放的平台并发许可。
     */
    public static final class Permit implements AutoCloseable {

        private final Semaphore semaphore;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        /**
         * 释放本次平台调用占用的并发许可；重复关闭不会增加许可数量。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
