package org.opengoofy.index12306.ai.agentservice.workflow.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.entity.AgentWorkflowEntity;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowType;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * 提供智能体业务工作流的持久化查询和并发锁定能力。
 */
@Mapper
public interface AgentWorkflowRepository extends BaseMapper<AgentWorkflowEntity> {

    /**
     * 查询会话中指定类型且尚未结束、未过期的工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @param workflowType 工作流类型
     * @param terminalStages 不应被恢复的终态集合
     * @param now 当前时间
     * @return 可继续推进的工作流
     */
    @Select("""
            <script>
            SELECT * FROM t_agent_workflow
            WHERE user_id = #{userId}
              AND conversation_id = #{conversationId}
              AND workflow_type = #{workflowType}
              AND stage NOT IN
              <foreach collection="terminalStages" item="stage" open="(" separator="," close=")">
                #{stage}
              </foreach>
              AND expires_at > #{now}
            ORDER BY updated_at DESC
            LIMIT 1
            </script>
            """)
    Optional<AgentWorkflowEntity> findFirstByUserIdAndConversationIdAndWorkflowType(
            @Param("userId") String userId,
            @Param("conversationId") String conversationId,
            @Param("workflowType") WorkflowType workflowType,
            @Param("terminalStages") Collection<WorkflowStage> terminalStages,
            @Param("now") Instant now);

    /**
     * 对活动作用域加写锁读取记录，用于串行化同类型工作流的新建或恢复。
     *
     * @param activeScopeKey 用户、会话和工作流类型组成的唯一作用域键
     * @return 已锁定的工作流；不存在时返回空
     */
    @Select("SELECT * FROM t_agent_workflow WHERE active_scope_key = #{activeScopeKey} FOR UPDATE")
    Optional<AgentWorkflowEntity> findLockedByActiveScopeKey(@Param("activeScopeKey") String activeScopeKey);

    /**
     * 使用数据库写锁读取工作流，避免同一阶段被并发请求重复推进。
     *
     * @param workflowId 工作流标识
     * @return 已锁定的工作流
     */
    @Select("SELECT * FROM t_agent_workflow WHERE id = #{workflowId} FOR UPDATE")
    Optional<AgentWorkflowEntity> findLockedById(@Param("workflowId") String workflowId);
}
