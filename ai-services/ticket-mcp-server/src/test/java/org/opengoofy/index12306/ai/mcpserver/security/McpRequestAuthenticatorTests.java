package org.opengoofy.index12306.ai.mcpserver.security;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.mcpserver.config.TicketMcpProperties;
import org.springaicommunity.mcp.annotation.McpMeta;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 MCP 身份签名、有效期和随机数防重放边界。
 */
class McpRequestAuthenticatorTests {

    private static final String SECRET = "test-only-mcp-internal-secret-1234567890";
    private static final Instant NOW = Instant.parse("2026-07-16T01:00:00Z");

    /**
     * 验证合法签名能够还原可信用户身份，且相同随机数不能再次使用。
     *
     * @throws Exception HMAC 算法不可用时抛出
     */
    @Test
    void acceptsValidSignatureAndRejectsReplay() throws Exception {
        // 使用固定时钟构造服务端鉴权器，消除测试时间漂移。
        McpRequestAuthenticator authenticator = new McpRequestAuthenticator(
                properties(), Clock.fixed(NOW, ZoneOffset.UTC));
        McpMeta metadata = signedMetadata("nonce-a", NOW.toEpochMilli(), "user-a");

        // 首次调用应返回签名覆盖的身份，第二次调用应命中防重放校验。
        McpCallerIdentity identity = authenticator.authenticate(metadata);
        assertThat(identity.userId()).isEqualTo("user-a");
        assertThat(identity.conversationId()).isEqualTo("conversation-a");
        assertThatThrownBy(() -> authenticator.authenticate(metadata))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("already been used");
    }

    /**
     * 验证签名后修改用户标识会被拒绝，模型不能借此查询其他用户数据。
     *
     * @throws Exception HMAC 算法不可用时抛出
     */
    @Test
    void rejectsTamperedUserIdentity() throws Exception {
        // 先生成合法签名，再只替换签名覆盖的 userId 字段。
        McpRequestAuthenticator authenticator = new McpRequestAuthenticator(
                properties(), Clock.fixed(NOW, ZoneOffset.UTC));
        Map<String, Object> tampered = new LinkedHashMap<>(signedMetadata(
                "nonce-b", NOW.toEpochMilli(), "user-a").meta());
        tampered.put("userId", "user-b");

        // 字段与签名不一致时必须在访问任何业务服务前失败。
        assertThatThrownBy(() -> authenticator.authenticate(new McpMeta(tampered)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("signature");
    }

    /**
     * 创建测试使用的完整 MCP 配置。
     *
     * @return MCP 配置
     */
    private TicketMcpProperties properties() {
        // 下游地址不会在鉴权单元测试中发起真实请求。
        URI local = URI.create("http://127.0.0.1:1");
        return new TicketMcpProperties(
                SECRET, Duration.ofMinutes(2), local, local, local,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 10, 20, 30, 20);
    }

    /**
     * 按生产规范生成一组签名 MCP 元数据。
     *
     * @param nonce 随机数
     * @param timestamp 毫秒时间戳
     * @param userId 用户标识
     * @return 签名元数据
     * @throws Exception HMAC 算法不可用时抛出
     */
    private McpMeta signedMetadata(String nonce, long timestamp, String userId) throws Exception {
        // 固定字段顺序必须与 Agent 签名器和 MCP 鉴权器一致。
        String timestampText = Long.toString(timestamp);
        String canonical = String.join("\n",
                "request-a", userId, "alice", "conversation-a", "turn-a",
                "", "", timestampText, nonce);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));

        // 元数据中的身份字段不属于模型工具参数模式。
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", "request-a");
        metadata.put("userId", userId);
        metadata.put("username", "alice");
        metadata.put("conversationId", "conversation-a");
        metadata.put("turnId", "turn-a");
        metadata.put("timestamp", timestampText);
        metadata.put("nonce", nonce);
        metadata.put("signature", signature);
        return new McpMeta(metadata);
    }
}
