package org.opengoofy.index12306.ai.agentservice.memory.repository;

import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
     * 分页查询当前用户自己的会话。
     *
     * @param userId 用户标识
     * @param pageable 分页和排序条件
     * @return 用户会话分页
     */
    Page<ConversationEntity> findByUserId(String userId, Pageable pageable);

    /**
     * 使用数据库写锁读取会话，保护消息序号分配和并发轮次写入。
     *
     * @param conversationId 会话标识
     * @return 锁定的会话
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConversationEntity c where c.id = :conversationId")
    Optional<ConversationEntity> findLockedById(@Param("conversationId") String conversationId);
}
