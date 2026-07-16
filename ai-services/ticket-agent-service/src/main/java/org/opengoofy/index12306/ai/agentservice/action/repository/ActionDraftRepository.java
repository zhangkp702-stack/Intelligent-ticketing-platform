package org.opengoofy.index12306.ai.agentservice.action.repository;

import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.action.domain.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.domain.AgentActionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 高风险操作草案持久化访问接口。
 */
public interface ActionDraftRepository extends JpaRepository<ActionDraftEntity, String> {

    /**
     * 查询同一轮次已经生成的同类草案，支持模型工具幂等重试。
     *
     * @param turnId 对话轮次标识
     * @param actionType 操作类型
     * @return 已有草案
     */
    Optional<ActionDraftEntity> findByTurnIdAndActionType(String turnId, AgentActionType actionType);

    /**
     * 使用数据库写锁读取草案，保护确认令牌的一次性消费。
     *
     * @param actionId 草案标识
     * @return 锁定的草案
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ActionDraftEntity a where a.id = :actionId")
    Optional<ActionDraftEntity> findLockedById(@Param("actionId") String actionId);
}
