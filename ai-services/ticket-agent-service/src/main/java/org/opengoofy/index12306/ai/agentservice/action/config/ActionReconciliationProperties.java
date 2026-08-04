package org.opengoofy.index12306.ai.agentservice.action.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * UNKNOWN 动作对账的重试和消费者租约配置。
 *
 * @param maxAttempts 最大查询次数
 * @param retryDelay 每次查询失败后的基础等待时间
 * @param leaseDuration MQ 消费节点持有对账任务的时间
 */
@ConfigurationProperties(prefix = "index12306.agent.action.reconciliation")
public record ActionReconciliationProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30s") Duration retryDelay,
        @DefaultValue("2m") Duration leaseDuration) {
}
