package org.opengoofy.index12306.ai.agentservice.workflow.dao.repository;

import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.entity.AgentWorkflowEntity;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * 提供智能体业务工作流的持久化查询和并发锁定能力。
 */
public interface AgentWorkflowRepository extends JpaRepository<AgentWorkflowEntity, String> {

    /**
     * 查询会话中最近一个尚未结束且未过期的工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @param terminalStages 不应被恢复的终态集合
     * @param now 当前时间
     * @return 可继续推进的工作流
     */
    Optional<AgentWorkflowEntity> findFirstByUserIdAndConversationIdAndStageNotInAndExpiresAtAfterOrderByUpdatedAtDesc(
            String userId,
            String conversationId,
            Collection<WorkflowStage> terminalStages,
            Instant now);

    /**
     * 使用数据库写锁读取工作流，避免同一阶段被并发请求重复推进。
     *
     * @param workflowId 工作流标识
     * @return 已锁定的工作流
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from AgentWorkflowEntity w where w.id = :workflowId")
    Optional<AgentWorkflowEntity> findLockedById(@Param("workflowId") String workflowId);
}
