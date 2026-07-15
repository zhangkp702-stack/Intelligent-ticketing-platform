package org.opengoofy.index12306.ai.agentservice.memory.repository;

import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TopicEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TopicStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 会话主题持久化访问接口。
 */
public interface TopicRepository extends JpaRepository<TopicEntity, String> {

    /**
     * 查询会话中最近活跃的指定状态主题。
     *
     * @param conversationId 会话标识
     * @param status 主题状态
     * @param pageable 数量限制
     * @return 按最近活跃时间倒序排列的主题
     */
    List<TopicEntity> findByConversationIdAndStatusOrderByLastActiveAtDesc(
            String conversationId,
            TopicStatus status,
            Pageable pageable);

    /**
     * 在会话边界内查询主题，防止跨会话引用。
     *
     * @param topicId 主题标识
     * @param conversationId 会话标识
     * @return 匹配主题
     */
    Optional<TopicEntity> findByIdAndConversationId(String topicId, String conversationId);

    /**
     * 使用数据库写锁读取主题，保护摘要版本推进。
     *
     * @param topicId 主题标识
     * @return 锁定的主题
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TopicEntity t where t.id = :topicId")
    Optional<TopicEntity> findLockedById(@Param("topicId") String topicId);
}
