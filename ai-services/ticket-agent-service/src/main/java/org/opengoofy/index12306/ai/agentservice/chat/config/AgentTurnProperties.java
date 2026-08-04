package org.opengoofy.index12306.ai.agentservice.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 服务端轮次提交令牌和在线执行租约配置。
 *
 * @param submissionSecret 轮次提交令牌 HMAC 密钥
 * @param submissionTtl 预创建轮次允许首次提交的时间窗口
 * @param executionLease 在线轮次单个执行者持有数据库执行权的时间窗口
 */
@ConfigurationProperties(prefix = "index12306.agent.turn")
public record AgentTurnProperties(
        @DefaultValue("") String submissionSecret,
        @DefaultValue("10m") Duration submissionTtl,
        @DefaultValue("2m") Duration executionLease) {

    /**
     * 校验轮次令牌和租约配置，避免无效时长破坏提交和执行边界。
     */
    public AgentTurnProperties {
        // 提交和执行窗口必须为正数，零值会让新创建的轮次立即失效。
        if (submissionTtl == null || submissionTtl.isZero() || submissionTtl.isNegative()) {
            throw new IllegalArgumentException("submissionTtl 必须大于 0");
        }
        if (executionLease == null || executionLease.isZero() || executionLease.isNegative()) {
            throw new IllegalArgumentException("executionLease 必须大于 0");
        }
    }
}
