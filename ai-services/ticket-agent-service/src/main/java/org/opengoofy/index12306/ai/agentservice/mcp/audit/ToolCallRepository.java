package org.opengoofy.index12306.ai.agentservice.mcp.audit;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * MCP 工具调用审计记录访问接口。
 */
public interface ToolCallRepository extends JpaRepository<ToolCallEntity, String> {

    /**
     * 统计同一请求已经持久化的工具调用数量。
     *
     * @param requestId 请求标识
     * @return 已有工具调用数量
     */
    long countByRequestId(String requestId);
}
