-- 为每个服务端 Turn 保存严格递增的 SSE 事件，支持跨实例断线续传和终态补发。
CREATE TABLE t_agent_stream_event (
    id VARCHAR(32) NOT NULL,
    turn_id VARCHAR(32) NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload_json TEXT NOT NULL,
    terminal BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_stream_turn_sequence UNIQUE (turn_id, event_sequence)
);

CREATE INDEX idx_agent_stream_turn_sequence
    ON t_agent_stream_event (turn_id, event_sequence);
CREATE INDEX idx_agent_stream_turn_terminal
    ON t_agent_stream_event (turn_id, terminal, event_sequence);
