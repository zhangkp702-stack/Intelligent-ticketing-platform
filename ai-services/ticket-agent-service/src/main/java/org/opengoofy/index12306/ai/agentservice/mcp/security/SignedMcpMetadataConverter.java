package org.opengoofy.index12306.ai.agentservice.mcp.security;

import org.opengoofy.index12306.ai.agentservice.mcp.config.AgentMcpProperties;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 Spring AI 工具上下文转换为带 HMAC 的 MCP 元数据，使用户身份不进入模型参数模式。
 */
public class SignedMcpMetadataConverter implements ToolContextToMcpMetaConverter {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int NONCE_BYTES = 18;

    private final byte[] secret;
    private final Clock clock;
    private final SecureRandom secureRandom;

    /**
     * 创建使用系统时钟和安全随机源的 MCP 元数据签名器。
     *
     * @param properties MCP 内部密钥配置
     */
    public SignedMcpMetadataConverter(AgentMcpProperties properties) {
        this(properties, Clock.systemUTC(), new SecureRandom());
    }

    /**
     * 创建可注入时间和随机源的签名器，供生产与边界测试复用。
     *
     * @param properties MCP 内部密钥配置
     * @param clock 当前时间来源
     * @param secureRandom 安全随机数来源
     */
    SignedMcpMetadataConverter(AgentMcpProperties properties, Clock clock, SecureRandom secureRandom) {
        // MCP 客户端启用时必须提供足够长度的共享密钥，禁止使用空密钥签名。
        Assert.hasText(properties.internalSecret(), "TICKET_MCP_INTERNAL_SECRET must be configured");
        Assert.isTrue(properties.internalSecret().length() >= 32,
                "TICKET_MCP_INTERNAL_SECRET must contain at least 32 characters");
        this.secret = properties.internalSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    /**
     * 校验必需工具上下文并生成含时间戳、随机数和签名的 MCP 元数据。
     *
     * @param toolContext Spring AI 当前工具调用上下文
     * @return 发送给 MCP 服务的签名元数据
     */
    @Override
    public Map<String, Object> convert(ToolContext toolContext) {
        // 从业务入口显式传入的工具上下文读取身份和审计关联字段。
        Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
        String requestId = required(context, McpToolContextFactory.REQUEST_ID);
        String userId = required(context, McpToolContextFactory.USER_ID);
        String username = optional(context, McpToolContextFactory.USERNAME);
        String conversationId = required(context, McpToolContextFactory.CONVERSATION_ID);
        String turnId = required(context, McpToolContextFactory.TURN_ID);
        String actionId = optional(context, McpToolContextFactory.ACTION_ID);
        String payloadHash = optional(context, McpToolContextFactory.PAYLOAD_HASH);
        String timestamp = Long.toString(clock.millis());
        String nonce = createNonce();

        // 签名覆盖全部身份、会话和防重放字段，任一字段被修改都会校验失败。
        String canonical = String.join("\n",
                requestId, userId, username, conversationId, turnId,
                actionId, payloadHash, timestamp, nonce);
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(canonical));

        // 返回新映射，避免改写 ChatClient 持有的原始工具上下文。
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(McpToolContextFactory.REQUEST_ID, requestId);
        metadata.put(McpToolContextFactory.USER_ID, userId);
        metadata.put(McpToolContextFactory.USERNAME, username);
        metadata.put(McpToolContextFactory.CONVERSATION_ID, conversationId);
        metadata.put(McpToolContextFactory.TURN_ID, turnId);
        metadata.put(McpToolContextFactory.ACTION_ID, actionId);
        metadata.put(McpToolContextFactory.PAYLOAD_HASH, payloadHash);
        metadata.put("timestamp", timestamp);
        metadata.put("nonce", nonce);
        metadata.put("signature", signature);
        return Map.copyOf(metadata);
    }

    /**
     * 读取非空工具上下文字段。
     *
     * @param context 工具上下文映射
     * @param key 字段名
     * @return 非空字段文本
     */
    private String required(Map<String, Object> context, String key) {
        // 业务身份不完整时中止工具调用，不能退化为匿名请求。
        String value = optional(context, key);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Missing MCP tool context: " + key);
        }
        return value;
    }

    /**
     * 读取允许为空的工具上下文字段。
     *
     * @param context 工具上下文映射
     * @param key 字段名
     * @return 字段文本或空字符串
     */
    private String optional(Map<String, Object> context, String key) {
        // 可选字段统一为空字符串以保持签名规范稳定。
        Object value = context.get(key);
        return value == null ? "" : value.toString();
    }

    /**
     * 生成单次工具调用随机数用于服务端防重放。
     *
     * @return Base64URL 随机数
     */
    private String createNonce() {
        // 使用密码学安全随机数，避免并发调用产生可预测或重复随机数。
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
    }

    /**
     * 使用共享密钥计算 HMAC-SHA256。
     *
     * @param canonical 规范签名文本
     * @return 原始签名字节
     */
    private byte[] sign(String canonical) {
        // Mac 实例不在线程间共享，确保并发工具调用安全。
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to calculate MCP identity signature", ex);
        }
    }
}
