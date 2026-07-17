package org.opengoofy.index12306.ai.agentservice.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 配置智能体 SSE 请求使用的受控异步执行器和容器级兜底超时。
 */
@Configuration
@EnableConfigurationProperties(AgentChatProperties.class)
public class AgentChatWebConfiguration implements WebMvcConfigurer {

    private static final int CORE_POOL_SIZE = 8;
    private static final int MAX_POOL_SIZE = 32;
    private static final int QUEUE_CAPACITY = 200;
    private static final long CONTAINER_TIMEOUT_GRACE_MILLIS = 5_000L;

    private final AgentChatProperties properties;

    /**
     * 创建智能体 Web 异步配置。
     *
     * @param properties 在线对话超时配置
     */
    public AgentChatWebConfiguration(AgentChatProperties properties) {
        this.properties = properties;
    }

    /**
     * 创建有界的 MVC 异步执行器，避免 SSE 使用无限创建线程的默认执行器。
     *
     * @return 智能体 SSE 请求专用执行器
     */
    @Bean("agentMvcTaskExecutor")
    public ThreadPoolTaskExecutor agentMvcTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 有界线程数和等待队列可防止慢模型请求在高负载下耗尽服务进程资源。
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("agent-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * 将响应式 SSE 适配到专用执行器，并让业务层有时间先发送友好超时事件。
     *
     * @param configurer Spring MVC 异步支持配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 容器超时略晚于业务超时，确保 CHAT_TIMEOUT 事件能够写回浏览器后再关闭连接。
        configurer.setTaskExecutor(agentMvcTaskExecutor());
        configurer.setDefaultTimeout(
                properties.responseTimeout().toMillis() + CONTAINER_TIMEOUT_GRACE_MILLIS);
    }
}
