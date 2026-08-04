package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.TurnEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

/**
 * 问答轮次持久化访问接口。
 */
@Mapper
public interface TurnRepository extends BaseMapper<TurnEntity> {

    /**
     * 根据会话和请求标识查询幂等轮次。
     *
     * @param conversationId 会话标识
     * @param requestId 请求标识
     * @return 已存在轮次
     */
    @Select("SELECT * FROM t_agent_turn WHERE conversation_id = #{conversationId} AND request_id = #{requestId}")
    Optional<TurnEntity> findByConversationIdAndRequestId(
            @Param("conversationId") String conversationId,
            @Param("requestId") String requestId);

    /**
     * 查询摘要边界之后最近完成的问答轮次。
     *
     * @param conversationId 会话标识
     * @param status 轮次状态
     * @param sequenceNo 摘要覆盖到的消息序号
     * @param excludedTurnId 当前正在执行、需要排除的轮次标识
     * @param limit 最近轮次数量限制
     * @return 按助手消息序号倒序排列的完整轮次
     */
    @Select("SELECT t.* FROM t_agent_turn t "
            + "JOIN t_agent_message user_message ON t.user_message_id = user_message.id "
            + "JOIN t_agent_message assistant_message ON t.assistant_message_id = assistant_message.id "
            + "WHERE t.conversation_id = #{conversationId} AND t.status = #{status} "
            + "AND user_message.sequence_no > #{sequenceNo} AND assistant_message.sequence_no > #{sequenceNo} "
            + "AND (#{excludedTurnId} IS NULL OR t.id <> #{excludedTurnId}) "
            + "ORDER BY assistant_message.sequence_no DESC LIMIT #{limit}")
    List<TurnEntity> findRecentCompletedTurns(
            @Param("conversationId") String conversationId,
            @Param("status") TurnStatus status,
            @Param("sequenceNo") long sequenceNo,
            @Param("excludedTurnId") String excludedTurnId,
            @Param("limit") int limit);

    /**
     * 使用数据库写锁读取轮次，保护最终状态更新。
     *
     * @param turnId 轮次标识
     * @return 锁定的轮次
     */
    @Select("SELECT * FROM t_agent_turn WHERE id = #{turnId} FOR UPDATE")
    Optional<TurnEntity> findLockedById(@Param("turnId") String turnId);

    /**
     * 查询租约已经到期的运行中轮次，供后台恢复器竞争接管。
     *
     * @param status 运行中状态
     * @param now 当前数据库比较时间
     * @param limit 单次扫描上限
     * @return 按租约到期时间升序排列的候选轮次
     */
    @Select("SELECT * FROM t_agent_turn WHERE status = #{status} "
            + "AND lease_until IS NOT NULL AND lease_until <= #{now} "
            + "ORDER BY lease_until ASC LIMIT #{limit}")
    List<TurnEntity> findExpiredRunningTurns(
            @Param("status") TurnStatus status,
            @Param("now") Instant now,
            @Param("limit") int limit);
}
