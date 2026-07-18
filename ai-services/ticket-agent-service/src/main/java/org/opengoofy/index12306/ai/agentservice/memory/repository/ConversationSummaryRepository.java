package org.opengoofy.index12306.ai.agentservice.memory.repository;

import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 提供会话唯一摘要的持久化访问。
 */
public interface ConversationSummaryRepository extends JpaRepository<ConversationSummaryEntity, String> {

    /**
     * 按会话读取当前唯一摘要。
     *
     * @param conversationId 会话标识
     * @return 会话摘要
     */
    Optional<ConversationSummaryEntity> findByConversationId(String conversationId);

    /**
     * 加写锁读取会话摘要，保护摘要边界和版本的原子推进。
     *
     * @param conversationId 会话标识
     * @return 锁定的会话摘要
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ConversationSummaryEntity s where s.conversationId = :conversationId")
    Optional<ConversationSummaryEntity> findLockedByConversationId(
            @Param("conversationId") String conversationId);
}
