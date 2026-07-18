package org.opengoofy.index12306.ai.agentservice.memory.repository;

import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TurnEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 问答轮次持久化访问接口。
 */
public interface TurnRepository extends JpaRepository<TurnEntity, String> {

    /**
     * 根据会话和请求标识查询幂等轮次。
     *
     * @param conversationId 会话标识
     * @param requestId 请求标识
     * @return 已存在轮次
     */
    Optional<TurnEntity> findByConversationIdAndRequestId(String conversationId, String requestId);

    /**
     * 使用数据库写锁读取轮次，保护最终状态更新。
     *
     * @param turnId 轮次标识
     * @return 锁定的轮次
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TurnEntity t where t.id = :turnId")
    Optional<TurnEntity> findLockedById(@Param("turnId") String turnId);
}
