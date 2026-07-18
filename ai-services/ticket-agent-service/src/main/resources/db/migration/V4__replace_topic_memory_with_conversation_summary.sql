DROP TABLE t_agent_context_route_log;

DROP INDEX idx_agent_context_topic_created ON t_agent_context_snapshot;
ALTER TABLE t_agent_context_snapshot DROP COLUMN topic_id;
ALTER TABLE t_agent_context_snapshot ADD COLUMN summary_version INT;
ALTER TABLE t_agent_context_snapshot ADD COLUMN summarized_through_sequence BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_agent_context_conversation_created
    ON t_agent_context_snapshot (conversation_id, created_at);

DROP TABLE t_agent_summary_task;
DROP TABLE t_agent_memory_summary;
DROP TABLE t_agent_topic;

DROP INDEX idx_agent_turn_topic_started ON t_agent_turn;
ALTER TABLE t_agent_turn DROP COLUMN topic_id;
CREATE INDEX idx_agent_turn_conversation_started
    ON t_agent_turn (conversation_id, started_at);

DROP INDEX idx_agent_message_topic_sequence ON t_agent_message;
ALTER TABLE t_agent_message DROP COLUMN topic_id;

ALTER TABLE t_agent_conversation DROP COLUMN active_topic_id;
ALTER TABLE t_agent_model_call DROP COLUMN topic_id;
ALTER TABLE t_agent_tool_call DROP COLUMN topic_id;
ALTER TABLE t_agent_action_draft DROP COLUMN topic_id;

CREATE TABLE t_agent_conversation_summary (
    id VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    summary_content TEXT NOT NULL,
    structured_state TEXT,
    summarized_through_sequence BIGINT NOT NULL DEFAULT 0,
    summary_version INT NOT NULL DEFAULT 0,
    source_message_count INT NOT NULL DEFAULT 0,
    provider_id VARCHAR(64),
    candidate_id VARCHAR(128),
    model_id VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_conversation_summary UNIQUE (conversation_id)
);

CREATE TABLE t_agent_summary_task (
    id VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(32) NOT NULL,
    desired_through_sequence BIGINT NOT NULL,
    processing_through_sequence BIGINT,
    expected_summary_version INT NOT NULL,
    event_version BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    next_retry_at TIMESTAMP(3),
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP(3),
    mq_message_id VARCHAR(128),
    published_at TIMESTAMP(3),
    failure_category VARCHAR(64),
    failure_message VARCHAR(512),
    started_at TIMESTAMP(3),
    finished_at TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_summary_task_conversation UNIQUE (conversation_id)
);

CREATE INDEX idx_agent_summary_task_dispatch
    ON t_agent_summary_task (status, next_retry_at, updated_at);
