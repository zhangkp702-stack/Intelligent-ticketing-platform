package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话删除时关联数据的持久化清理接口。
 */
@Mapper
public interface ConversationDeletionRepository {

    /**
     * 删除迁移前遗留的动作对账记录；待旧表退役后随表移除。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_action_reconciliation WHERE action_id IN "
            + "(SELECT id FROM t_agent_action_draft WHERE conversation_id = #{conversationId})")
    void deleteActionReconciliationsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的可靠 Inbox 消费记录。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_reliable_inbox_consumption WHERE namespace = 'agent-action-reconciliation' "
            + "AND event_id IN (SELECT event_id FROM t_reliable_outbox_event "
            + "WHERE namespace = 'agent-action-reconciliation' AND aggregate_id IN "
            + "(SELECT id FROM t_agent_action_draft WHERE conversation_id = #{conversationId}))")
    void deleteActionInboxByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的可靠 Outbox 事件。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_reliable_outbox_event WHERE namespace = 'agent-action-reconciliation' "
            + "AND aggregate_id IN (SELECT id FROM t_agent_action_draft "
            + "WHERE conversation_id = #{conversationId})")
    void deleteActionOutboxByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的可靠命令审计记录。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_reliable_command_audit WHERE namespace = 'agent-action-execution' "
            + "AND command_id IN (SELECT id FROM t_agent_action_draft "
            + "WHERE conversation_id = #{conversationId})")
    void deleteActionCommandAuditsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的可靠动作命令。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_reliable_command WHERE namespace = 'agent-action-execution' "
            + "AND command_id IN (SELECT id FROM t_agent_action_draft "
            + "WHERE conversation_id = #{conversationId})")
    void deleteActionCommandsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除迁移前遗留的动作执行记录；待旧表正式下线后可随表一起移除。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_action_execution WHERE action_id IN "
            + "(SELECT id FROM t_agent_action_draft WHERE conversation_id = #{conversationId})")
    void deleteLegacyActionExecutionsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的高风险操作草案。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_action_draft WHERE conversation_id = #{conversationId}")
    void deleteActionDraftsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的工作流状态。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_workflow WHERE conversation_id = #{conversationId}")
    void deleteWorkflowsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的 MCP 工具调用审计记录。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_tool_call WHERE conversation_id = #{conversationId}")
    void deleteToolCallsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的模型调用审计记录。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_model_call WHERE conversation_id = #{conversationId}")
    void deleteModelCallsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的上下文快照。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_context_snapshot WHERE conversation_id = #{conversationId}")
    void deleteContextSnapshotsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的异步摘要任务。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_summary_task WHERE conversation_id = #{conversationId}")
    void deleteSummaryTasksByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的摘要内容。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_conversation_summary WHERE conversation_id = #{conversationId}")
    void deleteSummariesByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话各轮次关联的持久化任务计划和执行结果。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_task_execution WHERE turn_id IN "
            + "(SELECT id FROM t_agent_turn WHERE conversation_id = #{conversationId})")
    void deleteTaskExecutionsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话各轮次关联的可重放 SSE 事件。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_stream_event WHERE turn_id IN "
            + "(SELECT id FROM t_agent_turn WHERE conversation_id = #{conversationId})")
    void deleteStreamEventsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的问答轮次。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_turn WHERE conversation_id = #{conversationId}")
    void deleteTurnsByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除会话关联的消息。
     *
     * @param conversationId 会话标识
     */
    @Delete("DELETE FROM t_agent_message WHERE conversation_id = #{conversationId}")
    void deleteMessagesByConversationId(@Param("conversationId") String conversationId);
}
