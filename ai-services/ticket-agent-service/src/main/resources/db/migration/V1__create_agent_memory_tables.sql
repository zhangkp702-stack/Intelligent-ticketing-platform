-- 智能体会话数据使用独立非分片数据库，不与票务、订单或支付表建立外键。
CREATE TABLE t_agent_conversation (
    id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    title VARCHAR(200),
    status VARCHAR(32) NOT NULL,
    active_topic_id VARCHAR(32),
    last_message_sequence BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_agent_conversation_user_updated
    ON t_agent_conversation (user_id, updated_at);

CREATE TABLE t_agent_topic (
    id VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    topic_key VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    short_summary VARCHAR(1000),
    structured_state TEXT,
    status VARCHAR(32) NOT NULL,
    last_active_at TIMESTAMP(3) NOT NULL,
    summary_version INT NOT NULL DEFAULT 0,
    summarized_through_sequence BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_topic_key UNIQUE (conversation_id, topic_key)
);

CREATE INDEX idx_agent_topic_conversation_active
    ON t_agent_topic (conversation_id, status, last_active_at);

CREATE TABLE t_agent_turn (
    id VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    topic_id VARCHAR(32),
    request_id VARCHAR(64) NOT NULL,
    user_message_id VARCHAR(32) NOT NULL,
    assistant_message_id VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3),
    failure_category VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_turn_request UNIQUE (conversation_id, request_id)
);

CREATE INDEX idx_agent_turn_topic_started
    ON t_agent_turn (topic_id, started_at);

CREATE TABLE t_agent_message (
    id VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    topic_id VARCHAR(32),
    turn_id VARCHAR(32),
    sequence_no BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    content_format VARCHAR(32) NOT NULL,
    token_count INT NOT NULL DEFAULT 0,
    request_id VARCHAR(64),
    idempotency_key VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_message_sequence UNIQUE (conversation_id, sequence_no),
    CONSTRAINT uk_agent_message_idempotency UNIQUE (conversation_id, idempotency_key)
);

CREATE INDEX idx_agent_message_topic_sequence
    ON t_agent_message (topic_id, sequence_no);
CREATE INDEX idx_agent_message_conversation_role_sequence
    ON t_agent_message (conversation_id, role, sequence_no);
CREATE INDEX idx_agent_message_turn
    ON t_agent_message (turn_id);

CREATE TABLE t_agent_memory_summary (
    id VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    topic_id VARCHAR(32) NOT NULL,
    version_no INT NOT NULL,
    from_sequence BIGINT NOT NULL,
    through_sequence BIGINT NOT NULL,
    summary_content TEXT NOT NULL,
    structured_state TEXT,
    source_message_count INT NOT NULL,
    provider_id VARCHAR(64),
    candidate_id VARCHAR(128),
    model_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_summary_version UNIQUE (topic_id, version_no)
);

CREATE INDEX idx_agent_summary_topic_status
    ON t_agent_memory_summary (topic_id, status, version_no);

CREATE TABLE t_agent_summary_task (
    id VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    topic_id VARCHAR(32) NOT NULL,
    from_sequence BIGINT NOT NULL,
    through_sequence BIGINT NOT NULL,
    expected_summary_version INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    next_retry_at TIMESTAMP(3),
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP(3),
    failure_category VARCHAR(64),
    failure_message VARCHAR(512),
    started_at TIMESTAMP(3),
    finished_at TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_summary_task_range UNIQUE (topic_id, through_sequence)
);

CREATE INDEX idx_agent_summary_task_dispatch
    ON t_agent_summary_task (status, next_retry_at, created_at);

CREATE TABLE t_agent_context_snapshot (
    id VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    topic_id VARCHAR(32) NOT NULL,
    summary_id VARCHAR(32),
    message_from_sequence BIGINT,
    message_through_sequence BIGINT,
    selected_message_ids TEXT,
    estimated_token_count INT NOT NULL DEFAULT 0,
    context_hash VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_context_request UNIQUE (request_id)
);

CREATE INDEX idx_agent_context_topic_created
    ON t_agent_context_snapshot (topic_id, created_at);

CREATE TABLE t_agent_context_route_log (
    id VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    current_message_id VARCHAR(32) NOT NULL,
    candidate_topic_ids TEXT NOT NULL,
    selected_topic_id VARCHAR(32),
    decision VARCHAR(32) NOT NULL,
    confidence DECIMAL(5, 4),
    model_call_id VARCHAR(32),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_route_request UNIQUE (request_id)
);

CREATE INDEX idx_agent_route_conversation_created
    ON t_agent_context_route_log (conversation_id, created_at);

CREATE TABLE t_agent_model_call (
    id VARCHAR(32) NOT NULL,
    request_id VARCHAR(64),
    conversation_id VARCHAR(32),
    topic_id VARCHAR(32),
    turn_id VARCHAR(32),
    role VARCHAR(32) NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    candidate_id VARCHAR(128) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    attempt_no INT NOT NULL,
    fallback_index INT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    failure_category VARCHAR(64),
    latency_millis BIGINT NOT NULL,
    prompt_tokens INT,
    completion_tokens INT,
    total_tokens INT,
    first_chunk_emitted BOOLEAN NOT NULL DEFAULT FALSE,
    exception_type VARCHAR(256),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_agent_model_call_request
    ON t_agent_model_call (request_id, attempt_no);
CREATE INDEX idx_agent_model_call_role_created
    ON t_agent_model_call (role, created_at);
CREATE INDEX idx_agent_model_call_provider_created
    ON t_agent_model_call (provider_id, created_at);
