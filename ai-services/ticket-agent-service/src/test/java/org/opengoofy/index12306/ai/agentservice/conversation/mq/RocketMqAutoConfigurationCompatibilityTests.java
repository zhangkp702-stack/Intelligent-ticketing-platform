package org.opengoofy.index12306.ai.agentservice.conversation.mq;

import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 RocketMQ Starter 能被 Spring Boot 3 的自动配置机制发现。
 */
class RocketMqAutoConfigurationCompatibilityTests {

    /**
     * 验证依赖包通过 AutoConfiguration.imports 注册 RocketMQ 自动配置。
     */
    @Test
    void exposesRocketMqAutoConfigurationToSpringBoot3() {
        // 直接读取 Spring Boot 的自动配置候选项，避免连接真实 RocketMQ 服务。
        ImportCandidates candidates = ImportCandidates.load(
                AutoConfiguration.class,
                getClass().getClassLoader());

        assertThat(candidates.getCandidates())
                .contains(RocketMQAutoConfiguration.class.getName());
    }
}
