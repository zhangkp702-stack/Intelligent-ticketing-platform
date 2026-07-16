-- 工具审计仅保存关联标识、参数指纹、计数、耗时和异常类型，不保存参数与响应正文。
CREATE TABLE t_agent_tool_call (
    id VARCHAR(32) NOT NULL,
    request_id VARCHAR(64),
    conversation_id VARCHAR(32),
    topic_id VARCHAR(32),
    turn_id VARCHAR(32),
    tool_name VARCHAR(64) NOT NULL,
    mcp_server VARCHAR(64) NOT NULL,
    invocation_no INT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    latency_millis BIGINT NOT NULL,
    failure_category VARCHAR(64),
    request_fingerprint VARCHAR(64) NOT NULL,
    response_item_count INT,
    exception_type VARCHAR(256),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_agent_tool_call_request
    ON t_agent_tool_call (request_id, invocation_no);
CREATE INDEX idx_agent_tool_call_turn_created
    ON t_agent_tool_call (turn_id, created_at);
CREATE INDEX idx_agent_tool_call_tool_created
    ON t_agent_tool_call (tool_name, created_at);
