package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationSummaryEntity;

import java.util.Optional;

/**
 * 提供会话唯一摘要的持久化访问。
 */
@Mapper
public interface ConversationSummaryRepository extends BaseMapper<ConversationSummaryEntity> {

    /**
     * 按会话读取当前唯一摘要。
     *
     * @param conversationId 会话标识
     * @return 会话摘要
     */
    @Select("SELECT * FROM t_agent_conversation_summary WHERE conversation_id = #{conversationId}")
    Optional<ConversationSummaryEntity> findByConversationId(@Param("conversationId") String conversationId);

    /**
     * 加写锁读取会话摘要，保护摘要边界和版本的原子推进。
     *
     * @param conversationId 会话标识
     * @return 锁定的会话摘要
     */
    @Select("SELECT * FROM t_agent_conversation_summary WHERE conversation_id = #{conversationId} FOR UPDATE")
    Optional<ConversationSummaryEntity> findLockedByConversationId(
            @Param("conversationId") String conversationId);
}
