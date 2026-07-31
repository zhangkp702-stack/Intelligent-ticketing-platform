package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;

import java.util.List;
import java.util.Optional;

/**
 * 会话持久化访问接口。
 */
@Mapper
public interface ConversationRepository extends BaseMapper<ConversationEntity> {

    /**
     * 分页查询当前用户自己的会话。
     *
     * @param userId 用户标识
     * @param offset 跳过的记录数
     * @param limit 最大返回数量
     * @return 用户会话分页
     */
    @Select("SELECT * FROM t_agent_conversation WHERE user_id = #{userId} "
            + "ORDER BY updated_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ConversationEntity> findByUserId(
            @Param("userId") String userId,
            @Param("offset") long offset,
            @Param("limit") long limit);

    /**
     * 统计用户可见会话总数，为服务层组装分页结果提供准确总量。
     *
     * @param userId 用户标识
     * @return 用户会话总数
     */
    @Select("SELECT COUNT(*) FROM t_agent_conversation WHERE user_id = #{userId}")
    long countByUserId(@Param("userId") String userId);

    /**
     * 使用数据库写锁读取会话，保护消息序号分配和并发轮次写入。
     *
     * @param conversationId 会话标识
     * @return 锁定的会话
     */
    @Select("SELECT * FROM t_agent_conversation WHERE id = #{conversationId} FOR UPDATE")
    Optional<ConversationEntity> findLockedById(@Param("conversationId") String conversationId);
}
