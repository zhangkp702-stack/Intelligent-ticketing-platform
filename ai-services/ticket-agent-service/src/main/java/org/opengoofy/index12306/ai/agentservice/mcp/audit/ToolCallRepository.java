package org.opengoofy.index12306.ai.agentservice.mcp.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MCP 工具调用审计记录访问接口。
 */
@Mapper
public interface ToolCallRepository extends BaseMapper<ToolCallEntity> {

    /**
     * 统计同一请求已经持久化的工具调用数量。
     *
     * @param requestId 请求标识
     * @return 已有工具调用数量
     */
    @Select("SELECT COUNT(*) FROM t_agent_tool_call WHERE request_id = #{requestId}")
    long countByRequestId(@Param("requestId") String requestId);
}
