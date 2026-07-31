package org.opengoofy.index12306.ai.agentservice.action.dao.repository;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * 高风险操作草案持久化访问接口。
 */
@Mapper
public interface ActionDraftRepository extends BaseMapper<ActionDraftEntity> {

    /**
     * 查询同一轮次已经生成的同类草案，支持模型工具幂等重试。
     *
     * @param turnId 对话轮次标识
     * @param actionType 操作类型
     * @return 已有草案
     */
    @Select("SELECT * FROM t_agent_action_draft WHERE turn_id = #{turnId} AND action_type = #{actionType}")
    Optional<ActionDraftEntity> findByTurnIdAndActionType(
            @Param("turnId") String turnId,
            @Param("actionType") AgentActionType actionType);

    /**
     * 查询同一轮次已经生成的全部高风险操作草案。
     *
     * @param turnId 对话轮次标识
     * @return 当前轮次草案列表
     */
    @Select("SELECT * FROM t_agent_action_draft WHERE turn_id = #{turnId}")
    List<ActionDraftEntity> findAllByTurnId(@Param("turnId") String turnId);

    /**
     * 查询会话最近创建的高风险操作，用于页面刷新后恢复确认或结果卡片。
     *
     * @param conversationId 会话标识
     * @return 最近操作草案
     */
    @Select("SELECT * FROM t_agent_action_draft WHERE conversation_id = #{conversationId} "
            + "ORDER BY created_at DESC LIMIT 1")
    Optional<ActionDraftEntity> findFirstByConversationIdOrderByCreatedAtDesc(
            @Param("conversationId") String conversationId);

    /**
     * 使用数据库写锁读取草案，保护确认令牌的一次性消费。
     *
     * @param actionId 草案标识
     * @return 锁定的草案
     */
    @Select("SELECT * FROM t_agent_action_draft WHERE id = #{actionId} FOR UPDATE")
    Optional<ActionDraftEntity> findLockedById(@Param("actionId") String actionId);
}
