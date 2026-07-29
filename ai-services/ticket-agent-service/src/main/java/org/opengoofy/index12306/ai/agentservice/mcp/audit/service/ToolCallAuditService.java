package org.opengoofy.index12306.ai.agentservice.mcp.audit.service;

import org.opengoofy.index12306.ai.agentservice.mcp.audit.ToolCallOutcome;

/**
 * 定义 MCP 工具调用审计的独立持久化能力。
 */
public interface ToolCallAuditService {

    /**
     * 为当前请求分配调用序号并保存审计记录。
     *
     * @param event 尚未分配调用序号的工具调用事件
     * @return 持久化审计记录标识
     */
    String record(ToolCallAuditEvent event);

    /**
     * 尚未分配持久化序号的工具调用事件。
     *
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param toolName 工具名称
     * @param mcpServer MCP 服务名称
     * @param outcome 调用结果
     * @param latencyMillis 调用耗时
     * @param failureCategory 失败类别
     * @param requestFingerprint 参数指纹
     * @param responseItemCount 响应条目数
     * @param exceptionType 异常类型
     */
    record ToolCallAuditEvent(
            String requestId,
            String conversationId,
            String turnId,
            String toolName,
            String mcpServer,
            ToolCallOutcome outcome,
            long latencyMillis,
            String failureCategory,
            String requestFingerprint,
            Integer responseItemCount,
            String exceptionType) {
    }
}
