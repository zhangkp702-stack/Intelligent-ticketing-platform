package org.opengoofy.index12306.ai.agentservice.action;

import org.opengoofy.index12306.ai.agentservice.action.config.AgentActionProperties;
import org.opengoofy.index12306.ai.agentservice.action.domain.ActionDraftEntity;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 为购票草案生成可重复计算、不可伪造且由状态机保证一次性的确认令牌。
 */
@Component
public class ConfirmationTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    /**
     * 创建确认令牌服务。
     *
     * @param properties 高风险操作安全配置
     */
    public ConfirmationTokenService(AgentActionProperties properties) {
        this.secret = properties.confirmationSecret().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 根据不可变草案字段生成稳定确认令牌。
     *
     * @param action 购票草案
     * @return Base64URL HMAC 确认令牌
     */
    public String issue(ActionDraftEntity action) {
        // 令牌覆盖用户、参数指纹和过期时间，不能跨用户、跨草案或修改参数复用。
        byte[] signature = sign(canonical(action));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    /**
     * 使用恒定时间比较校验用户提交的确认令牌。
     *
     * @param action 购票草案
     * @param token 用户提交令牌
     * @return 令牌与当前草案完全匹配时返回 true
     */
    public boolean matches(ActionDraftEntity action, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            // URL 安全 Base64 解码失败统一视为无效令牌。
            byte[] actual = Base64.getUrlDecoder().decode(token);
            return MessageDigest.isEqual(sign(canonical(action)), actual);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 按固定顺序构造确认令牌签名原文。
     *
     * @param action 购票草案
     * @return 规范签名文本
     */
    private String canonical(ActionDraftEntity action) {
        // 过期时间使用 Epoch 毫秒，避免时区和文本格式差异。
        return String.join("\n",
                action.getId(),
                action.getUserId(),
                action.getConversationId(),
                action.getTurnId(),
                action.getActionType().name(),
                action.getPayloadHash(),
                Long.toString(action.getConfirmationExpiresAt().toEpochMilli()));
    }

    /**
     * 计算确认令牌 HMAC-SHA256。
     *
     * @param canonical 规范签名文本
     * @return 原始签名字节
     */
    private byte[] sign(String canonical) {
        // 每次创建独立 Mac 实例，避免并发确认共享非线程安全对象。
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法生成操作确认令牌", ex);
        }
    }
}
