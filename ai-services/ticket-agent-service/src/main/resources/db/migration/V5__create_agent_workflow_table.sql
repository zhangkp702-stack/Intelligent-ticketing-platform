CREATE TABLE t_agent_workflow (
    id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    workflow_type VARCHAR(32) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    context_json TEXT NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_agent_workflow_user_conversation
    ON t_agent_workflow (user_id, conversation_id, updated_at);
