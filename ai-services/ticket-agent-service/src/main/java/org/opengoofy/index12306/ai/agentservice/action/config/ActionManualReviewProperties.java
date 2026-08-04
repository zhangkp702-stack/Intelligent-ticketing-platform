package org.opengoofy.index12306.ai.agentservice.action.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 人工重新开启只读对账入口的最小启用与鉴权配置。
 *
 * @param enabled 是否注册内部人工处置入口
 * @param secret 人工处置入口的独立共享密钥
 */
@ConfigurationProperties(prefix = "index12306.agent.action.manual-review")
public record ActionManualReviewProperties(
        @DefaultValue("false") boolean enabled,
        String secret) {

    /**
     * 使用常量时间比较校验内部人工处置密钥。
     *
     * @param presentedSecret 请求携带的密钥
     * @return 密钥匹配且入口已启用时返回 true
     */
    public boolean matches(String presentedSecret) {
        // 入口默认关闭；即使网关配置错误，也要求独立密钥才能重新开启只读对账。
        if (!enabled || secret == null || secret.isBlank() || presentedSecret == null) {
            return false;
        }
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                presentedSecret.getBytes(StandardCharsets.UTF_8));
    }
}
