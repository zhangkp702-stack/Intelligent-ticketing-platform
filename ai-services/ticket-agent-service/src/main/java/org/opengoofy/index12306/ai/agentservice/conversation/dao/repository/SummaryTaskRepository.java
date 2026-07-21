package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.SummaryTaskEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.SummaryTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 提供会话摘要任务的合并、发布和领取查询。
 */
public interface SummaryTaskRepository extends JpaRepository<SummaryTaskEntity, String> {

    /**
     * 按会话读取唯一任务行。
     *
     * @param conversationId 会话标识
     * @return 摘要任务
     */
    Optional<SummaryTaskEntity> findByConversationId(String conversationId);

    /**
     * 加写锁读取会话任务，保护目标边界合并。
     *
     * @param conversationId 会话标识
     * @return 锁定任务
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SummaryTaskEntity t where t.conversationId = :conversationId")
    Optional<SummaryTaskEntity> findLockedByConversationId(
            @Param("conversationId") String conversationId);

    /**
     * 加写锁读取待消费任务，保护跨实例领取。
     *
     * @param taskId 任务标识
     * @return 锁定任务
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SummaryTaskEntity t where t.id = :taskId")
    Optional<SummaryTaskEntity> findLockedById(@Param("taskId") String taskId);

    /**
     * 查询有限数量的待发布任务，防止单次调度长期占用线程。
     *
     * @param status 任务状态
     * @return 最早更新的待发布任务
     */
    List<SummaryTaskEntity> findTop100ByStatusOrderByUpdatedAtAsc(SummaryTaskStatus status);

    /**
     * 锁定租约已过期的运行中任务，供发布器恢复消费者宕机留下的任务。
     *
     * @param status 运行中状态
     * @param leaseUntil 租约截止上界
     * @return 最早到期的任务
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SummaryTaskEntity> findTop100ByStatusAndLeaseUntilLessThanEqualOrderByLeaseUntilAsc(
            SummaryTaskStatus status,
            Instant leaseUntil);

    /**
     * 锁定已经到达重试时间的任务，供数据库 Outbox 补偿发布。
     *
     * @param status 等待重试状态
     * @param nextRetryAt 重试时间上界
     * @return 最早到期的任务
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SummaryTaskEntity> findTop100ByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            SummaryTaskStatus status,
            Instant nextRetryAt);
}
