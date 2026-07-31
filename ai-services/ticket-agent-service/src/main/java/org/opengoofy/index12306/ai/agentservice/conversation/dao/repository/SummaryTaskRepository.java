package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.SummaryTaskEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.SummaryTaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 提供会话摘要任务的合并、发布和领取查询。
 */
@Mapper
public interface SummaryTaskRepository extends BaseMapper<SummaryTaskEntity> {

    /**
     * 按会话读取唯一任务行。
     *
     * @param conversationId 会话标识
     * @return 摘要任务
     */
    @Select("SELECT * FROM t_agent_summary_task WHERE conversation_id = #{conversationId}")
    Optional<SummaryTaskEntity> findByConversationId(@Param("conversationId") String conversationId);

    /**
     * 加写锁读取会话任务，保护目标边界合并。
     *
     * @param conversationId 会话标识
     * @return 锁定任务
     */
    @Select("SELECT * FROM t_agent_summary_task WHERE conversation_id = #{conversationId} FOR UPDATE")
    Optional<SummaryTaskEntity> findLockedByConversationId(
            @Param("conversationId") String conversationId);

    /**
     * 加写锁读取待消费任务，保护跨实例领取。
     *
     * @param taskId 任务标识
     * @return 锁定任务
     */
    @Select("SELECT * FROM t_agent_summary_task WHERE id = #{taskId} FOR UPDATE")
    Optional<SummaryTaskEntity> findLockedById(@Param("taskId") String taskId);

    /**
     * 查询有限数量的待发布任务，防止单次调度长期占用线程。
     *
     * @param status 任务状态
     * @return 最早更新的待发布任务
     */
    @Select("SELECT * FROM t_agent_summary_task WHERE status = #{status} ORDER BY updated_at ASC LIMIT 100")
    List<SummaryTaskEntity> findTop100ByStatusOrderByUpdatedAtAsc(@Param("status") SummaryTaskStatus status);

    /**
     * 锁定租约已过期的运行中任务，供发布器恢复消费者宕机留下的任务。
     *
     * @param status 运行中状态
     * @param leaseUntil 租约截止上界
     * @return 最早到期的任务
     */
    @Select("SELECT * FROM t_agent_summary_task WHERE status = #{status} AND lease_until <= #{leaseUntil} "
            + "ORDER BY lease_until ASC LIMIT 100 FOR UPDATE")
    List<SummaryTaskEntity> findTop100ByStatusAndLeaseUntilLessThanEqualOrderByLeaseUntilAsc(
            @Param("status") SummaryTaskStatus status,
            @Param("leaseUntil") Instant leaseUntil);

    /**
     * 锁定已经到达重试时间的任务，供数据库 Outbox 补偿发布。
     *
     * @param status 等待重试状态
     * @param nextRetryAt 重试时间上界
     * @return 最早到期的任务
     */
    @Select("SELECT * FROM t_agent_summary_task WHERE status = #{status} AND next_retry_at <= #{nextRetryAt} "
            + "ORDER BY next_retry_at ASC LIMIT 100 FOR UPDATE")
    List<SummaryTaskEntity> findTop100ByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            @Param("status") SummaryTaskStatus status,
            @Param("nextRetryAt") Instant nextRetryAt);
}
