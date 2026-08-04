-- UNKNOWN 操作在同一 Agent 数据库中创建 Outbox 事件，并持久化 MQ 消费领取状态用于去重。
CREATE TABLE t_agent_action_reconciliation (
    id VARCHAR(32) NOT NULL,
    action_id VARCHAR(32) NOT NULL,
    event_version BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    next_retry_at TIMESTAMP(3),
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP(3),
    consumer_name VARCHAR(128),
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
    CONSTRAINT uk_agent_action_reconciliation_action UNIQUE (action_id)
);

CREATE INDEX idx_agent_action_reconciliation_publish
    ON t_agent_action_reconciliation (status, updated_at);
CREATE INDEX idx_agent_action_reconciliation_retry
    ON t_agent_action_reconciliation (status, next_retry_at, lease_until);
