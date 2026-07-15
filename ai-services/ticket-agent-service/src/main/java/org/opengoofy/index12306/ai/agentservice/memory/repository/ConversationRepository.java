package org.opengoofy.index12306.ai.agentservice.memory.repository;

import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 会话持久化访问接口。
 */
public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {

    /**
     * 使用数据库写锁读取会话，保护消息序号分配和活动主题更新。
     *
     * @param conversationId 会话标识
     * @return 锁定的会话
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConversationEntity c where c.id = :conversationId")
    Optional<ConversationEntity> findLockedById(@Param("conversationId") String conversationId);
}
