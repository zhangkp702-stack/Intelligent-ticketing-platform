package org.opengoofy.index12306.ai.agentservice.memory.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * 注册记忆配置和摘要专用异步执行器。
 */
@Configuration
@EnableScheduling
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

}
