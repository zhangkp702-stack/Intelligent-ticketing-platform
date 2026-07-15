package org.opengoofy.index12306.ai.agentservice.memory.repository;

import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.memory.domain.SummaryTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 异步摘要任务持久化访问接口。
 */
public interface SummaryTaskRepository extends JpaRepository<SummaryTaskEntity, String> {

    /**
     * 根据主题和结束消息边界查询幂等任务。
     *
     * @param topicId 主题标识
     * @param throughSequence 结束消息序号
     * @return 已存在任务
     */
    Optional<SummaryTaskEntity> findByTopicIdAndThroughSequence(String topicId, long throughSequence);

    /**
     * 使用数据库写锁读取任务，保护领取和状态流转。
     *
     * @param taskId 任务标识
     * @return 锁定的摘要任务
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SummaryTaskEntity t where t.id = :taskId")
    Optional<SummaryTaskEntity> findLockedById(@Param("taskId") String taskId);
}
