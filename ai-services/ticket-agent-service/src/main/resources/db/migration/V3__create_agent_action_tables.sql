-- 高风险写操作使用独立草案和执行记录；确认令牌不保存明文，令牌有效性由服务端 HMAC 校验。
CREATE TABLE t_agent_action_draft (
    id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    topic_id VARCHAR(32) NOT NULL,
    turn_id VARCHAR(32) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json TEXT NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    confirmation_expires_at TIMESTAMP(3) NOT NULL,
    confirmation_consumed_at TIMESTAMP(3),
    execution_id VARCHAR(32),
    result_json TEXT,
    result_reference VARCHAR(128),
    failure_category VARCHAR(64),
    finished_at TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_action_turn_type UNIQUE (turn_id, action_type)
);

CREATE INDEX idx_agent_action_user_status
    ON t_agent_action_draft (user_id, status, updated_at);
CREATE INDEX idx_agent_action_conversation_created
    ON t_agent_action_draft (conversation_id, created_at);

CREATE TABLE t_agent_action_execution (
    id VARCHAR(32) NOT NULL,
    action_id VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3),
    result_reference VARCHAR(128),
    response_fingerprint VARCHAR(64),
    failure_category VARCHAR(64),
    exception_type VARCHAR(256),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_action_execution_action UNIQUE (action_id),
    CONSTRAINT uk_agent_action_execution_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_agent_action_execution_request
    ON t_agent_action_execution (request_id, started_at);
