package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import jakarta.persistence.LockModeType;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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
     * 查询摘要边界之后最近完成的问答轮次。
     *
     * @param conversationId 会话标识
     * @param status 轮次状态
     * @param sequenceNo 摘要覆盖到的消息序号
     * @param excludedTurnId 当前正在执行、需要排除的轮次标识
     * @param pageable 最近轮次数量限制
     * @return 按助手消息序号倒序排列的完整轮次
     */
    @Query("""
            select t from TurnEntity t, MessageEntity userMessage, MessageEntity assistantMessage
            where t.conversationId = :conversationId
              and t.status = :status
              and t.userMessageId = userMessage.id
              and t.assistantMessageId = assistantMessage.id
              and userMessage.sequenceNo > :sequenceNo
              and assistantMessage.sequenceNo > :sequenceNo
              and (:excludedTurnId is null or t.id <> :excludedTurnId)
            order by assistantMessage.sequenceNo desc
            """)
    List<TurnEntity> findRecentCompletedTurns(
            @Param("conversationId") String conversationId,
            @Param("status") TurnStatus status,
            @Param("sequenceNo") long sequenceNo,
            @Param("excludedTurnId") String excludedTurnId,
            Pageable pageable);

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
