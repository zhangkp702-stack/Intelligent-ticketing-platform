package org.opengoofy.index12306.ai.agentservice.mcp.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.AgentBaseEntity;

import java.time.Instant;
import java.util.Objects;

/**
 * 不保存工具参数和响应正文的 MCP 工具调用持久化审计记录。
 */
@Getter
@Entity
@Table(name = "t_agent_tool_call")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ToolCallEntity extends AgentBaseEntity {

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "conversation_id", length = 32)
    private String conversationId;

    @Column(name = "turn_id", length = 32)
    private String turnId;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "mcp_server", nullable = false, length = 64)
    private String mcpServer;

    @Column(name = "invocation_no", nullable = false)
    private int invocationNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private ToolCallOutcome outcome;

    @Column(name = "latency_millis", nullable = false)
    private long latencyMillis;

    @Column(name = "failure_category", length = 64)
    private String failureCategory;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "response_item_count")
    private Integer responseItemCount;

    @Column(name = "exception_type", length = 256)
    private String exceptionType;

    /**
     * 使用已验证的审计数据初始化持久化实体。
     *
     * @param data 工具调用审计数据
     * @param now 创建时间
     */
    private ToolCallEntity(ToolCallData data, Instant now) {
        super(now);
        this.requestId = data.requestId();
        this.conversationId = data.conversationId();
        this.turnId = data.turnId();
        this.toolName = Objects.requireNonNull(data.toolName(), "toolName");
        this.mcpServer = Objects.requireNonNull(data.mcpServer(), "mcpServer");
        this.invocationNo = Math.max(1, data.invocationNo());
        this.outcome = Objects.requireNonNull(data.outcome(), "outcome");
        this.latencyMillis = Math.max(0, data.latencyMillis());
        this.failureCategory = data.failureCategory();
        this.requestFingerprint = Objects.requireNonNull(data.requestFingerprint(), "requestFingerprint");
        this.responseItemCount = data.responseItemCount();
        this.exceptionType = data.exceptionType();
    }

    /**
     * 根据不含敏感正文的稳定元数据创建工具调用审计实体。
     *
     * @param data 工具调用审计数据
     * @param now 创建时间
     * @return 新建审计实体
     */
    public static ToolCallEntity create(ToolCallData data, Instant now) {
        // 审计实体只接受指纹和计数，不提供保存原始参数或响应正文的字段。
        return new ToolCallEntity(data, now);
    }

    /**
     * 工具调用审计所需的稳定元数据。
     *
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param toolName 工具名称
     * @param mcpServer MCP 服务名称
     * @param invocationNo 当前请求中的工具调用序号
     * @param outcome 调用结果
     * @param latencyMillis 调用耗时
     * @param failureCategory 失败类别
     * @param requestFingerprint 参数 SHA-256 指纹
     * @param responseItemCount 响应条目数
     * @param exceptionType 异常类型，不含异常正文
     */
    public record ToolCallData(
            String requestId,
            String conversationId,
            String turnId,
            String toolName,
            String mcpServer,
            int invocationNo,
            ToolCallOutcome outcome,
            long latencyMillis,
            String failureCategory,
            String requestFingerprint,
            Integer responseItemCount,
            String exceptionType) {
    }
}
