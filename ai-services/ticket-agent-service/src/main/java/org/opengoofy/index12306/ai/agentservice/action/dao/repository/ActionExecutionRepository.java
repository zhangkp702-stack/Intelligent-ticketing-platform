package org.opengoofy.index12306.ai.agentservice.action.dao.repository;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionExecutionEntity;

import java.util.Optional;

/**
 * 真实业务写调用执行审计仓储。
 */
@Mapper
public interface ActionExecutionRepository extends BaseMapper<ActionExecutionEntity> {

    /**
     * 根据草案标识查询唯一执行记录。
     *
     * @param actionId 草案标识
     * @return 执行记录
     */
    @Select("SELECT * FROM t_agent_action_execution WHERE action_id = #{actionId}")
    Optional<ActionExecutionEntity> findByActionId(@Param("actionId") String actionId);

    /**
     * 根据客户端幂等键查询已经领取的执行记录。
     *
     * @param idempotencyKey 确认幂等键
     * @return 执行记录
     */
    @Select("SELECT * FROM t_agent_action_execution WHERE idempotency_key = #{idempotencyKey}")
    Optional<ActionExecutionEntity> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}
