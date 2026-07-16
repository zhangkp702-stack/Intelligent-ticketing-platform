package org.opengoofy.index12306.ai.agentservice.mcp.security;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.mcp.config.AgentMcpProperties;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.springframework.ai.chat.model.ToolContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Agent 将显式工具上下文签名为模型不可编辑的 MCP 元数据。
 */
class SignedMcpMetadataConverterTests {

    private static final String SECRET = "test-only-mcp-internal-secret-1234567890";

    /**
     * 验证身份、时间戳和随机数均被 HMAC 覆盖，且原始上下文不被修改。
     *
     * @throws Exception HMAC 算法不可用时抛出
     */
    @Test
    void signsExplicitIdentityMetadata() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-16T01:00:00Z"), ZoneOffset.UTC);
        SecureRandom random = new FixedSecureRandom();
        SignedMcpMetadataConverter converter = new SignedMcpMetadataConverter(
                new AgentMcpProperties(SECRET), clock, random);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(McpToolContextFactory.REQUEST_ID, "request-a");
        context.put(McpToolContextFactory.USER_ID, "user-a");
        context.put(McpToolContextFactory.USERNAME, "alice");
        context.put(McpToolContextFactory.CONVERSATION_ID, "conversation-a");
        context.put(McpToolContextFactory.TURN_ID, "turn-a");
        context.put(McpToolContextFactory.TOPIC_ID, "topic-a");

        // 转换器复制上下文字段并添加防重放数据，不会把签名写回原始映射。
        Map<String, Object> metadata = converter.convert(new ToolContext(context));
        assertThat(context).doesNotContainKeys("timestamp", "nonce", "signature");
        assertThat(metadata).containsEntry("userId", "user-a");

        // 按服务端相同规范重新计算签名，确认所有身份字段都受 HMAC 保护。
        String canonical = String.join("\n",
                "request-a",
                "user-a",
                "alice",
                "conversation-a",
                "turn-a",
                "topic-a",
                metadata.get("timestamp").toString(),
                metadata.get("nonce").toString());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(metadata.get("signature")).isEqualTo(expected);
    }

    /**
     * 验证缺少用户标识时签名器直接拒绝调用，不能退化为匿名工具请求。
     */
    @Test
    void rejectsMissingRequiredIdentity() {
        SignedMcpMetadataConverter converter = new SignedMcpMetadataConverter(new AgentMcpProperties(SECRET));

        // 空工具上下文不能产生可发送到 MCP 服务的签名。
        assertThatThrownBy(() -> converter.convert(new ToolContext(Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");
    }

    /**
     * 为测试生成固定随机数字节，保证 nonce 和签名可重复断言。
     */
    private static final class FixedSecureRandom extends SecureRandom {

        /**
         * 使用固定字节填充随机数目标数组。
         *
         * @param bytes 待填充字节数组
         */
        @Override
        public void nextBytes(byte[] bytes) {
            // 固定值只用于测试，不进入生产签名器。
            Arrays.fill(bytes, (byte) 7);
        }
    }
}
