package org.opengoofy.index12306.ai.agentservice.action.dao.repository;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionExecutionEntity;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

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

    /**
     * 加写锁读取执行记录，保护 UNKNOWN 对账状态迁移。
     *
     * @param executionId 执行记录标识
     * @return 锁定执行记录
     */
    @Select("SELECT * FROM t_agent_action_execution WHERE id = #{executionId} FOR UPDATE")
    Optional<ActionExecutionEntity> findLockedById(@Param("executionId") String executionId);

    /**
     * 查询租约已经过期的执行中记录。
     *
     * @param now 当前时间
     * @return 可由恢复器转入 UNKNOWN 的候选记录
     */
    @Select("SELECT * FROM t_agent_action_execution "
            + "WHERE outcome = 'STARTED' AND lease_until <= #{now} "
            + "ORDER BY lease_until ASC LIMIT 100")
    List<ActionExecutionEntity> findExpiredStarted(@Param("now") Instant now);

    /**
     * 查询确认事务提交后长期没有执行器领取的排队记录。
     *
     * @param deadline 最晚允许排队时间
     * @return 可安全结束为失败的候选记录
     */
    @Select("SELECT * FROM t_agent_action_execution "
            + "WHERE outcome = 'QUEUED' AND created_at <= #{deadline} "
            + "ORDER BY created_at ASC LIMIT 100")
    List<ActionExecutionEntity> findAbandonedQueued(@Param("deadline") Instant deadline);
}
