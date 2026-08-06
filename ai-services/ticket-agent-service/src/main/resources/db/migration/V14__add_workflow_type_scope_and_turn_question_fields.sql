-- 活动工作流以“用户 + 会话 + 业务类型”为唯一作用域；终态和已过期历史记录不占用活动作用域。
ALTER TABLE t_agent_workflow
    ADD COLUMN active_scope_key VARCHAR(255);
ALTER TABLE t_agent_workflow
    ADD COLUMN context_schema_version INT NOT NULL DEFAULT 1;
ALTER TABLE t_agent_workflow
    ADD COLUMN last_input_sequence BIGINT;

UPDATE t_agent_workflow
SET active_scope_key = CONCAT(
        CHAR_LENGTH(user_id), ':', user_id, '|',
        CHAR_LENGTH(conversation_id), ':', conversation_id, '|', workflow_type)
WHERE stage NOT IN ('COMPLETED', 'EXPIRED')
  AND expires_at > CURRENT_TIMESTAMP(3);

CREATE UNIQUE INDEX uk_agent_workflow_active_scope
    ON t_agent_workflow (active_scope_key);

CREATE INDEX idx_agent_workflow_user_conversation_type_updated
    ON t_agent_workflow (user_id, conversation_id, workflow_type, updated_at);

-- 重写和输入消费状态与 Turn 同生命周期保存，后续阶段可保证每条用户输入都有可追踪的解析结果。
ALTER TABLE t_agent_turn
    ADD COLUMN rewrite_status VARCHAR(32);
ALTER TABLE t_agent_turn
    ADD COLUMN has_rewrite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE t_agent_turn
    ADD COLUMN question_resolution_json TEXT;
ALTER TABLE t_agent_turn
    ADD COLUMN input_consumption_status VARCHAR(32);
