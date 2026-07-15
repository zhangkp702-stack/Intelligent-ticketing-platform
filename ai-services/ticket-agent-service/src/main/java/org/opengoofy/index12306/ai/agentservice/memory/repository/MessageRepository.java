package org.opengoofy.index12306.ai.agentservice.memory.repository;

import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 原始消息持久化访问接口。
 */
public interface MessageRepository extends JpaRepository<MessageEntity, String> {

    /**
     * 根据会话和幂等键查找已经写入的消息。
     *
     * @param conversationId 会话标识
     * @param idempotencyKey 幂等键
     * @return 已存在消息
     */
    Optional<MessageEntity> findByConversationIdAndIdempotencyKey(
            String conversationId,
            String idempotencyKey);

    /**
     * 查询主题指定消息边界之后的全部未压缩消息。
     *
     * @param topicId 主题标识
     * @param sequenceNo 已压缩消息边界
     * @return 按会话序号升序排列的消息
     */
    List<MessageEntity> findByTopicIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
            String topicId,
            long sequenceNo);

    /**
     * 统计主题指定边界之后可参与新摘要的消息数。
     *
     * @param topicId 主题标识
     * @param sequenceNo 已压缩消息边界
     * @return 未压缩消息数
     */
    long countByTopicIdAndSequenceNoGreaterThan(String topicId, long sequenceNo);

    /**
     * 查询主题消息边界之后最近的消息，避免摘要失败时无界加载历史正文。
     *
     * @param topicId 主题标识
     * @param sequenceNo 已压缩消息边界
     * @param pageable 数量限制
     * @return 按消息序号倒序排列的最近消息
     */
    @Query("""
            select m from MessageEntity m
            where m.topicId = :topicId
              and m.sequenceNo > :sequenceNo
            order by m.sequenceNo desc
            """)
    List<MessageEntity> findRecentTopicMessages(
            @Param("topicId") String topicId,
            @Param("sequenceNo") long sequenceNo,
            Pageable pageable);

    /**
     * 查询会话最近的指定角色消息，可排除当前问题。
     *
     * @param conversationId 会话标识
     * @param role 消息角色
     * @param excludedMessageId 需要排除的当前消息标识
     * @param pageable 数量限制
     * @return 按消息序号倒序排列的消息
     */
    @Query("""
            select m from MessageEntity m
            where m.conversationId = :conversationId
              and m.role = :role
              and (:excludedMessageId is null or m.id <> :excludedMessageId)
            order by m.sequenceNo desc
            """)
    List<MessageEntity> findRecentByRole(
            @Param("conversationId") String conversationId,
            @Param("role") MessageRole role,
            @Param("excludedMessageId") String excludedMessageId,
            Pageable pageable);

    /**
     * 查询主题指定闭区间内用于摘要的原始消息。
     *
     * @param topicId 主题标识
     * @param fromSequence 起始消息序号
     * @param throughSequence 结束消息序号
     * @return 按序号升序排列的来源消息
     */
    List<MessageEntity> findByTopicIdAndSequenceNoBetweenOrderBySequenceNoAsc(
            String topicId,
            long fromSequence,
            long throughSequence);
}
