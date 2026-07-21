package org.opengoofy.index12306.ai.agentservice.action.dao.repository;


import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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
     * 查询同一轮次已经生成的全部高风险操作草案。
     *
     * @param turnId 对话轮次标识
     * @return 当前轮次草案列表
     */
    List<ActionDraftEntity> findAllByTurnId(String turnId);

    /**
     * 查询会话最近创建的高风险操作，用于页面刷新后恢复确认或结果卡片。
     *
     * @param conversationId 会话标识
     * @return 最近操作草案
     */
    Optional<ActionDraftEntity> findFirstByConversationIdOrderByCreatedAtDesc(String conversationId);

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
