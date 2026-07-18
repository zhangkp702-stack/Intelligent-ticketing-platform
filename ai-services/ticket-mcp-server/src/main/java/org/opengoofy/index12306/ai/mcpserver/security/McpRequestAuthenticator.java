package org.opengoofy.index12306.ai.mcpserver.security;

import org.opengoofy.index12306.ai.mcpserver.config.TicketMcpProperties;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 校验 Agent 放入 MCP 元数据的用户身份签名，防止模型参数伪造用户身份。
 */
@Component
public class McpRequestAuthenticator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAX_TRACKED_NONCES = 10_000;

    private final byte[] secret;
    private final long allowedClockSkewMillis;
    private final Clock clock;
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

    /**
     * 创建 MCP 请求鉴权器并验证共享密钥已经由外部环境提供。
     *
     * @param properties MCP 安全配置
     */
    @Autowired
    public McpRequestAuthenticator(TicketMcpProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的 MCP 请求鉴权器，供生产校验和时间边界测试复用。
     *
     * @param properties MCP 安全配置
     * @param clock 当前时间来源
     */
    McpRequestAuthenticator(TicketMcpProperties properties, Clock clock) {
        // MCP 服务必须在共享密钥缺失时拒绝启动，避免降级为匿名内部接口。
        Assert.hasText(properties.internalSecret(), "TICKET_MCP_INTERNAL_SECRET must be configured");
        Assert.isTrue(properties.internalSecret().length() >= 32,
                "TICKET_MCP_INTERNAL_SECRET must contain at least 32 characters");
        this.secret = properties.internalSecret().getBytes(StandardCharsets.UTF_8);
        this.allowedClockSkewMillis = properties.allowedClockSkew().toMillis();
        this.clock = clock;
    }

    /**
     * 验证 MCP 元数据中的必需字段、有效期、随机数和 HMAC 签名，并返回可信身份。
     *
     * @param meta MCP 请求元数据，不会出现在模型可填写的工具参数中
     * @return 已验证的调用者身份
     */
    public McpCallerIdentity authenticate(McpMeta meta) {
        // 提取签名覆盖的全部字段，任何必需字段缺失都直接拒绝调用。
        String requestId = required(meta, "requestId");
        String userId = required(meta, "userId");
        String username = optional(meta, "username");
        String conversationId = required(meta, "conversationId");
        String turnId = required(meta, "turnId");
        String actionId = optional(meta, "actionId");
        String payloadHash = optional(meta, "payloadHash");
        String timestampText = required(meta, "timestamp");
        String nonce = required(meta, "nonce");
        String signature = required(meta, "signature");

        // 过期签名和重复随机数均不得访问下游用户数据。
        long timestamp = parseTimestamp(timestampText);
        long now = clock.millis();
        if (Math.abs(now - timestamp) > allowedClockSkewMillis) {
            throw new SecurityException("MCP identity metadata has expired");
        }
        purgeExpiredNonces(now);
        if (usedNonces.putIfAbsent(nonce, timestamp) != null) {
            throw new SecurityException("MCP identity metadata has already been used");
        }

        // 使用恒定时间比较签名，失败时移除随机数以避免无效请求占满缓存。
        String canonical = canonical(
                requestId, userId, username, conversationId, turnId,
                actionId, payloadHash, timestampText, nonce);
        byte[] expected = sign(canonical);
        byte[] actual = decode(signature);
        if (!MessageDigest.isEqual(expected, actual)) {
            usedNonces.remove(nonce);
            throw new SecurityException("Invalid MCP identity signature");
        }
        return new McpCallerIdentity(
                requestId, userId, username, conversationId, turnId, actionId, payloadHash);
    }

    /**
     * 读取非空的 MCP 元数据字段。
     *
     * @param meta MCP 元数据
     * @param key 字段名
     * @return 非空字段值
     */
    private String required(McpMeta meta, String key) {
        // 将元数据统一转为文本后执行非空校验，避免接受 null 字符串。
        String value = optional(meta, key);
        if (!StringUtils.hasText(value)) {
            throw new SecurityException("Missing MCP identity metadata: " + key);
        }
        return value;
    }

    /**
     * 读取允许为空的 MCP 元数据字段。
     *
     * @param meta MCP 元数据
     * @param key 字段名
     * @return 字段文本，不存在时返回空字符串
     */
    private String optional(McpMeta meta, String key) {
        // 可选字段仍参与签名，缺失时使用稳定空值保持两端规范一致。
        Object value = meta == null ? null : meta.get(key);
        return value == null ? "" : value.toString();
    }

    /**
     * 将毫秒时间戳转换为长整数并拒绝非法格式。
     *
     * @param value 时间戳文本
     * @return 毫秒时间戳
     */
    private long parseTimestamp(String value) {
        // 非数字时间戳不能参与有效期判断。
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new SecurityException("Invalid MCP identity timestamp", ex);
        }
    }

    /**
     * 清理超出签名有效期的随机数，在异常流量下限制本地防重放缓存规模。
     *
     * @param now 当前毫秒时间戳
     */
    private void purgeExpiredNonces(long now) {
        // 正常情况下删除过期项；达到硬上限时失败关闭，不能通过清空缓存重新接受旧随机数。
        usedNonces.entrySet().removeIf(entry -> now - entry.getValue() > allowedClockSkewMillis);
        if (usedNonces.size() >= MAX_TRACKED_NONCES) {
            throw new SecurityException("MCP replay protection capacity has been reached");
        }
    }

    /**
     * 按固定字段顺序生成跨服务一致的签名原文。
     *
     * @param values 签名字段
     * @return 使用换行分隔的规范文本
     */
    private String canonical(String... values) {
        // 固定顺序和分隔符可避免字段拼接歧义。
        return String.join("\n", values);
    }

    /**
     * 计算规范文本的 HMAC-SHA256 签名。
     *
     * @param canonical 规范签名原文
     * @return 原始签名字节
     */
    private byte[] sign(String canonical) {
        // 每次调用创建 Mac 实例，避免在线程间共享非线程安全状态。
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to calculate MCP identity signature", ex);
        }
    }

    /**
     * 解码 URL 安全的 Base64 签名。
     *
     * @param signature Base64URL 签名
     * @return 原始签名字节
     */
    private byte[] decode(String signature) {
        // 非法 Base64 统一作为鉴权失败处理，不向调用方暴露内部细节。
        try {
            return Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException ex) {
            throw new SecurityException("Invalid MCP identity signature", ex);
        }
    }
}
