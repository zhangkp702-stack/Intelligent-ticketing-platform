package org.opengoofy.index12306.ai.agentservice.mcp.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.mcp.audit.ToolCallAuditService.ToolCallAuditEvent;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 在不记录参数和响应正文的前提下，为 MCP 工具回调增加持久化审计。
 */
public class AuditedToolCallback implements ToolCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditedToolCallback.class);
    private static final String MCP_SERVER = "index12306-ticket-mcp-server";

    private final ToolCallback delegate;
    private final ToolCallAuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * 创建审计工具回调装饰器。
     *
     * @param delegate 原始 MCP 工具回调
     * @param auditService 持久化审计服务
     * @param objectMapper JSON 结构解析器
     */
    public AuditedToolCallback(
            ToolCallback delegate,
            ToolCallAuditService auditService,
            ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回原始 MCP 工具定义，保持模型看到的参数模式不变。
     *
     * @return 原始工具定义
     */
    @Override
    public ToolDefinition getToolDefinition() {
        // 装饰器不修改工具名称、描述或输入模式。
        return delegate.getToolDefinition();
    }

    /**
     * 返回原始 MCP 工具元数据，保留只读和幂等提示。
     *
     * @return 原始工具元数据
     */
    @Override
    public ToolMetadata getToolMetadata() {
        // 工具安全提示直接委托给 MCP 回调。
        return delegate.getToolMetadata();
    }

    /**
     * 执行没有业务上下文的工具调用；签名转换器会拒绝缺少身份的请求并记录失败。
     *
     * @param toolInput 模型生成的工具参数 JSON
     * @return 工具响应文本
     */
    @Override
    public String call(String toolInput) {
        // 显式创建空上下文，确保无身份调用不会绕过统一审计路径。
        return call(toolInput, new ToolContext(Map.of()));
    }

    /**
     * 执行带显式 Agent 上下文的 MCP 工具调用并记录指纹、耗时和结果计数。
     *
     * @param toolInput 模型生成的工具参数 JSON
     * @param toolContext 包含用户与会话关联字段的工具上下文
     * @return 工具响应文本
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long started = System.nanoTime();
        Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
        String toolName = getToolDefinition().name();
        String requestId = text(context, McpToolContextFactory.REQUEST_ID);
        String turnId = text(context, McpToolContextFactory.TURN_ID);
        LOGGER.info("Agent开始调用MCP工具，tool={}, requestId={}, turnId={}", toolName, requestId, turnId);
        try {
            // 原始回调会将工具上下文转换为签名 MCP 元数据并调用远端工具。
            String result = delegate.call(toolInput, toolContext);
            Integer responseItemCount = countItems(result);
            LOGGER.info("Agent调用MCP工具成功，tool={}, requestId={}, turnId={}, durationMs={}, itemCount={}",
                    toolName, requestId, turnId,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), responseItemCount);
            record(toolInput, toolContext, ToolCallOutcome.SUCCESS, started, null, responseItemCount, null);
            return result;
        } catch (RuntimeException ex) {
            // 失败审计只保留异常类型和粗粒度类别，不保存可能含敏感信息的异常正文。
            LOGGER.warn("Agent调用MCP工具失败，tool={}, requestId={}, turnId={}, durationMs={}, exceptionType={}",
                    toolName, requestId, turnId,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), ex.getClass().getSimpleName());
            record(toolInput, toolContext, ToolCallOutcome.FAILURE, started, classify(ex), null,
                    ex.getClass().getName());
            throw ex;
        }
    }

    /**
     * 构造并尽力持久化工具调用审计，不让审计故障改变工具业务结果。
     *
     * @param toolInput 工具参数 JSON
     * @param toolContext 工具上下文
     * @param outcome 调用结果
     * @param started 开始纳秒时间
     * @param failureCategory 失败类别
     * @param responseItemCount 响应条目数
     * @param exceptionType 异常类型
     */
    private void record(
            String toolInput,
            ToolContext toolContext,
            ToolCallOutcome outcome,
            long started,
            String failureCategory,
            Integer responseItemCount,
            String exceptionType) {
        // 仅提取上下文关联字段，参数本身只计算不可逆 SHA-256 指纹。
        Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
        ToolCallAuditEvent event = new ToolCallAuditEvent(
                text(context, McpToolContextFactory.REQUEST_ID),
                text(context, McpToolContextFactory.CONVERSATION_ID),
                text(context, McpToolContextFactory.TURN_ID),
                getToolDefinition().name(),
                MCP_SERVER,
                outcome,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                failureCategory,
                fingerprint(toolInput),
                responseItemCount,
                exceptionType);
        try {
            // 审计使用独立事务；数据库异常只写日志，不覆盖真实工具结果或异常。
            auditService.record(event);
        } catch (RuntimeException auditException) {
            LOGGER.warn("Unable to persist MCP tool call audit for tool {}", getToolDefinition().name(), auditException);
        }
    }

    /**
     * 从工具上下文读取允许为空的关联字段。
     *
     * @param context 工具上下文属性
     * @param key 字段名
     * @return 字段文本或 null
     */
    private String text(Map<String, Object> context, String key) {
        // 空字符串统一转为 null，避免审计表出现无意义空值。
        Object value = context.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }

    /**
     * 计算工具参数 JSON 的 SHA-256 指纹。
     *
     * @param toolInput 工具参数 JSON
     * @return 64 位十六进制指纹
     */
    private String fingerprint(String toolInput) {
        // 指纹支持同参调用关联，但无法从审计表恢复原始参数。
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((toolInput == null ? "" : toolInput).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * 从工具响应结构估算顶层业务条目数量。
     *
     * @param result 工具响应文本
     * @return 可识别的条目数，无法识别时返回 null
     */
    private Integer countItems(String result) {
        // 优先统计数组；分页和余票响应分别统计 orders 与 trains 数组。
        try {
            JsonNode root = objectMapper.readTree(result);
            if (root.isArray()) {
                return root.size();
            }
            for (String field : new String[]{"orders", "trains"}) {
                JsonNode items = root.path(field);
                if (items.isArray()) {
                    return items.size();
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 将工具异常归并为不含敏感正文的稳定失败类别。
     *
     * @param exception 工具调用异常
     * @return 失败类别
     */
    private String classify(RuntimeException exception) {
        // 身份或参数问题与远端 MCP 调用故障分开统计。
        if (exception instanceof SecurityException) {
            return "SECURITY";
        }
        if (exception instanceof IllegalArgumentException) {
            return "VALIDATION";
        }
        return "MCP_CALL";
    }
}
