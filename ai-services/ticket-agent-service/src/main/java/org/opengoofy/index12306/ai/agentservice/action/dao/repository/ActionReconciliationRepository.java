package org.opengoofy.index12306.ai.agentservice.action.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionReconciliationEntity;
import org.opengoofy.index12306.ai.agentservice.action.enums.ActionReconciliationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 提供动作对账 Outbox 发布、Inbox 领取和过期恢复查询。
 */
@Mapper
public interface ActionReconciliationRepository extends BaseMapper<ActionReconciliationEntity> {

    /**
     * 按操作标识查询唯一对账任务。
     *
     * @param actionId 操作标识
     * @return 对账任务
     */
    @Select("SELECT * FROM t_agent_action_reconciliation WHERE action_id = #{actionId}")
    Optional<ActionReconciliationEntity> findByActionId(@Param("actionId") String actionId);

    /**
     * 加写锁读取对账事件，保护跨实例消费领取。
     *
     * @param eventId 事件标识
     * @return 锁定事件
     */
    @Select("SELECT * FROM t_agent_action_reconciliation WHERE id = #{eventId} FOR UPDATE")
    Optional<ActionReconciliationEntity> findLockedById(@Param("eventId") String eventId);

    /**
     * 查询有限批次的待发布 Outbox 事件。
     *
     * @param status 待发布状态
     * @return 最早更新的事件
     */
    @Select("SELECT * FROM t_agent_action_reconciliation WHERE status = #{status} "
            + "ORDER BY updated_at ASC LIMIT 100")
    List<ActionReconciliationEntity> findTop100ByStatusOrderByUpdatedAtAsc(
            @Param("status") ActionReconciliationStatus status);

    /**
     * 锁定消费租约已经到期的事件。
     *
     * @param status 运行状态
     * @param leaseUntil 租约截止上界
     * @return 到期事件
     */
    @Select("SELECT * FROM t_agent_action_reconciliation WHERE status = #{status} "
            + "AND lease_until <= #{leaseUntil} ORDER BY lease_until ASC LIMIT 100 FOR UPDATE")
    List<ActionReconciliationEntity> findExpiredRunning(
            @Param("status") ActionReconciliationStatus status,
            @Param("leaseUntil") Instant leaseUntil);

    /**
     * 锁定已经到达重试时间的事件。
     *
     * @param status 等待重试状态
     * @param nextRetryAt 重试时间上界
     * @return 到期事件
     */
    @Select("SELECT * FROM t_agent_action_reconciliation WHERE status = #{status} "
            + "AND next_retry_at <= #{nextRetryAt} ORDER BY next_retry_at ASC LIMIT 100 FOR UPDATE")
    List<ActionReconciliationEntity> findDueRetries(
            @Param("status") ActionReconciliationStatus status,
            @Param("nextRetryAt") Instant nextRetryAt);
}
