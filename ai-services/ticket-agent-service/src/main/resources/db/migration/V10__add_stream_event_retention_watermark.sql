-- 清理旧 SSE 事件后仍需保持序号单调递增，因此将已分配水位固化到 Turn 主记录。
ALTER TABLE t_agent_turn ADD COLUMN last_event_sequence BIGINT NOT NULL DEFAULT 0;

UPDATE t_agent_turn
SET last_event_sequence = (
    SELECT COALESCE(MAX(event_sequence), 0)
    FROM t_agent_stream_event
    WHERE t_agent_stream_event.turn_id = t_agent_turn.id
);

CREATE INDEX idx_agent_stream_created_turn
    ON t_agent_stream_event (created_at, turn_id);
