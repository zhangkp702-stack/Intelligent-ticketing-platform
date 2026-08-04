package org.opengoofy.index12306.ai.agentservice.chat.security;

import org.opengoofy.index12306.ai.agentservice.chat.config.AgentTurnProperties;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 为服务端预创建轮次签发并校验不可伪造的首次提交令牌。
 */
@Component
public class TurnSubmissionTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_LENGTH = 32;

    private final byte[] secret;

    /**
     * 创建轮次提交令牌服务并校验 HMAC 密钥强度。
     *
     * @param properties 服务端轮次协议配置
     */
    public TurnSubmissionTokenService(AgentTurnProperties properties) {
        String configuredSecret = properties.submissionSecret();
        if (!StringUtils.hasText(configuredSecret)
                || configuredSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("轮次提交令牌密钥至少需要 32 个字节");
        }
        // 复制不可变字节数组，后续每次签名创建独立 Mac 实例保证线程安全。
        this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 根据服务端轮次、用户和过期时间签发稳定提交令牌。
     *
     * @param turn 待提交轮次
     * @param userId 当前会话所有者
     * @return Base64URL HMAC 提交令牌
     */
    public String issue(TurnEntity turn, String userId) {
        // 令牌绑定服务端 ID 和身份边界，前端不能把令牌用于另一轮或另一会话。
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sign(canonical(turn, userId)));
    }

    /**
     * 使用恒定时间比较校验客户端回传的轮次提交令牌。
     *
     * @param turn 待提交或已提交轮次
     * @param userId 当前认证用户
     * @param token 客户端回传令牌
     * @return 令牌与轮次不可变字段完全匹配时返回 true
     */
    public boolean matches(TurnEntity turn, String userId, String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        try {
            // 非法 Base64 与签名不匹配统一返回 false，不暴露令牌解析细节。
            byte[] actual = Base64.getUrlDecoder().decode(token);
            return MessageDigest.isEqual(sign(canonical(turn, userId)), actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 按固定顺序构造轮次提交令牌的签名原文。
     *
     * @param turn 轮次实体
     * @param userId 会话所有者
     * @return 规范签名文本
     */
    private String canonical(TurnEntity turn, String userId) {
        // Epoch 毫秒避免时区与序列化格式差异影响重复校验。
        return String.join("\n",
                turn.getId(),
                userId,
                turn.getConversationId(),
                Long.toString(turn.getSubmissionExpiresAt().toEpochMilli()));
    }

    /**
     * 计算规范原文的 HMAC-SHA256 签名。
     *
     * @param canonical 规范签名文本
     * @return 原始签名字节
     */
    private byte[] sign(String canonical) {
        try {
            // Mac 不是线程安全对象，因此每次调用独立初始化。
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法生成轮次提交令牌", exception);
        }
    }
}
