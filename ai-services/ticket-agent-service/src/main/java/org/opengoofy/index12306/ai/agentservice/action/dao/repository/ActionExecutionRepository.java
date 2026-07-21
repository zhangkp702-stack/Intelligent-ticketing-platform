package org.opengoofy.index12306.ai.agentservice.action.dao.repository;


import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 真实业务写调用执行审计仓储。
 */
public interface ActionExecutionRepository extends JpaRepository<ActionExecutionEntity, String> {

    /**
     * 根据草案标识查询唯一执行记录。
     *
     * @param actionId 草案标识
     * @return 执行记录
     */
    Optional<ActionExecutionEntity> findByActionId(String actionId);

    /**
     * 根据客户端幂等键查询已经领取的执行记录。
     *
     * @param idempotencyKey 确认幂等键
     * @return 执行记录
     */
    Optional<ActionExecutionEntity> findByIdempotencyKey(String idempotencyKey);
}
