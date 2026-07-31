package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.MessageEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageType;

import java.util.List;
import java.util.Optional;

/**
 * 原始消息持久化访问接口。
 */
@Mapper
public interface MessageRepository extends BaseMapper<MessageEntity> {

    /**
     * 根据会话和幂等键查找已经写入的消息。
     *
     * @param conversationId 会话标识
     * @param idempotencyKey 幂等键
     * @return 已存在消息
     */
    @Select("SELECT * FROM t_agent_message WHERE conversation_id = #{conversationId} "
            + "AND idempotency_key = #{idempotencyKey}")
    Optional<MessageEntity> findByConversationIdAndIdempotencyKey(
            @Param("conversationId") String conversationId,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 游标查询会话中指定序号之前的文本消息。
     *
     * @param conversationId 会话标识
     * @param messageType 消息类型
     * @param beforeSequence 不包含的消息序号上界
     * @param limit 最大返回数量
     * @return 按消息序号倒序排列的历史消息
     */
    @Select("SELECT * FROM t_agent_message WHERE conversation_id = #{conversationId} "
            + "AND message_type = #{messageType} AND sequence_no < #{beforeSequence} "
            + "ORDER BY sequence_no DESC LIMIT #{limit}")
    List<MessageEntity> findConversationHistory(
            @Param("conversationId") String conversationId,
            @Param("messageType") MessageType messageType,
            @Param("beforeSequence") long beforeSequence,
            @Param("limit") int limit);

    /**
     * 查询会话指定消息边界之后的全部未压缩消息。
     *
     * @param conversationId 会话标识
     * @param sequenceNo 已压缩消息边界
     * @return 按会话序号升序排列的消息
     */
    @Select("SELECT * FROM t_agent_message WHERE conversation_id = #{conversationId} "
            + "AND sequence_no > #{sequenceNo} ORDER BY sequence_no ASC")
    List<MessageEntity> findByConversationIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
            @Param("conversationId") String conversationId,
            @Param("sequenceNo") long sequenceNo);

    /**
     * 统计会话指定边界之后可参与新摘要的消息数。
     *
     * @param conversationId 会话标识
     * @param sequenceNo 已压缩消息边界
     * @return 未压缩消息数
     */
    @Select("SELECT COUNT(*) FROM t_agent_message WHERE conversation_id = #{conversationId} "
            + "AND sequence_no > #{sequenceNo}")
    long countByConversationIdAndSequenceNoGreaterThan(
            @Param("conversationId") String conversationId,
            @Param("sequenceNo") long sequenceNo);

    /**
     * 查询会话消息边界之后最近的消息，避免摘要失败时无界加载历史正文。
     *
     * @param conversationId 会话标识
     * @param sequenceNo 已压缩消息边界
     * @param limit 数量限制
     * @return 按消息序号倒序排列的最近消息
     */
    @Select("SELECT * FROM t_agent_message WHERE conversation_id = #{conversationId} "
            + "AND sequence_no > #{sequenceNo} ORDER BY sequence_no DESC LIMIT #{limit}")
    List<MessageEntity> findRecentConversationMessages(
            @Param("conversationId") String conversationId,
            @Param("sequenceNo") long sequenceNo,
            @Param("limit") int limit);

    /**
     * 查询会话最近的指定角色消息，可排除当前问题。
     *
     * @param conversationId 会话标识
     * @param role 消息角色
     * @param excludedMessageId 需要排除的当前消息标识
     * @param limit 数量限制
     * @return 按消息序号倒序排列的消息
     */
    @Select("SELECT * FROM t_agent_message WHERE conversation_id = #{conversationId} "
            + "AND role = #{role} AND (#{excludedMessageId} IS NULL OR id <> #{excludedMessageId}) "
            + "ORDER BY sequence_no DESC LIMIT #{limit}")
    List<MessageEntity> findRecentByRole(
            @Param("conversationId") String conversationId,
            @Param("role") MessageRole role,
            @Param("excludedMessageId") String excludedMessageId,
            @Param("limit") int limit);

    /**
     * 查询会话指定闭区间内用于摘要的原始消息。
     *
     * @param conversationId 会话标识
     * @param fromSequence 起始消息序号
     * @param throughSequence 结束消息序号
     * @return 按序号升序排列的来源消息
     */
    @Select("SELECT * FROM t_agent_message WHERE conversation_id = #{conversationId} "
            + "AND sequence_no BETWEEN #{fromSequence} AND #{throughSequence} ORDER BY sequence_no ASC")
    List<MessageEntity> findByConversationIdAndSequenceNoBetweenOrderBySequenceNoAsc(
            @Param("conversationId") String conversationId,
            @Param("fromSequence") long fromSequence,
            @Param("throughSequence") long throughSequence);

    /**
     * 统计会话指定闭区间内实际参与本次摘要的消息数。
     *
     * @param conversationId 会话标识
     * @param fromSequence 起始消息序号
     * @param throughSequence 结束消息序号
     * @return 本次摘要来源消息数
     */
    @Select("SELECT COUNT(*) FROM t_agent_message WHERE conversation_id = #{conversationId} "
            + "AND sequence_no BETWEEN #{fromSequence} AND #{throughSequence}")
    long countByConversationIdAndSequenceNoBetween(
            @Param("conversationId") String conversationId,
            @Param("fromSequence") long fromSequence,
            @Param("throughSequence") long throughSequence);
}
