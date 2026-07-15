package org.opengoofy.index12306.ai.agentservice.memory.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.Executor;

/**
 * 注册记忆配置和摘要专用异步执行器。
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(AgentMemoryProperties.class)
public class AgentMemoryConfiguration {

    /**
     * 提供统一 UTC 时钟，便于任务状态机和集成测试控制时间语义。
     *
     * @return 系统 UTC 时钟
     */
    @Bean
    public Clock agentMemoryClock() {
        // 持久化统一使用绝对时间，展示层再按用户时区转换。
        return Clock.systemUTC();
    }

    /**
     * 创建与在线回答线程隔离的摘要任务执行器。
     *
     * @param properties 记忆和线程池配置
     * @return 摘要任务专用执行器
     */
    @Bean("agentSummaryExecutor")
    public Executor agentSummaryExecutor(AgentMemoryProperties properties) {
        AgentMemoryProperties.SummaryExecutor executorProperties = properties.summaryExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 摘要线程池使用独立容量，避免低优先级压缩任务阻塞用户在线回答。
        executor.setCorePoolSize(executorProperties.corePoolSize());
        executor.setMaxPoolSize(executorProperties.maxPoolSize());
        executor.setQueueCapacity(executorProperties.queueCapacity());
        executor.setThreadNamePrefix("agent-summary-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) executorProperties.awaitTermination().toSeconds());
        executor.initialize();
        return executor;
    }
}
