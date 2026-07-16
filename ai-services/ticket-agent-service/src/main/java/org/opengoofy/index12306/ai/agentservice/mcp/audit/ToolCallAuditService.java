package org.opengoofy.index12306.ai.agentservice.mcp.audit;

import org.opengoofy.index12306.ai.agentservice.mcp.audit.ToolCallEntity.ToolCallData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 使用独立事务持久化 MCP 工具调用审计。
 */
@Service
public class ToolCallAuditService {

    private final ToolCallRepository repository;
    private final Clock clock;

    /**
     * 创建工具调用审计服务。
     *
     * @param repository 工具调用审计仓储
     * @param clock 当前时间来源
     */
    public ToolCallAuditService(ToolCallRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 为当前请求分配调用序号并在独立事务中保存审计记录。
     *
     * @param event 尚未分配调用序号的工具调用事件
     * @return 持久化审计记录标识
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String record(ToolCallAuditEvent event) {
        // 同一模型轮次通常串行调用工具，按请求已有记录数生成便于排查的调用序号。
        int invocationNo = event.requestId() == null || event.requestId().isBlank()
                ? 1
                : Math.toIntExact(repository.countByRequestId(event.requestId()) + 1);
        ToolCallData data = new ToolCallData(
                event.requestId(),
                event.conversationId(),
                event.topicId(),
                event.turnId(),
                event.toolName(),
                event.mcpServer(),
                invocationNo,
                event.outcome(),
                event.latencyMillis(),
                event.failureCategory(),
                event.requestFingerprint(),
                event.responseItemCount(),
                event.exceptionType());

        // 审计与主对话事务隔离，即使工具失败也保留诊断记录。
        ToolCallEntity entity = ToolCallEntity.create(data, clock.instant());
        return repository.save(entity).getId();
    }

    /**
     * 尚未分配持久化序号的工具调用事件。
     *
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param topicId 主题标识
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
    public record ToolCallAuditEvent(
            String requestId,
            String conversationId,
            String topicId,
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
