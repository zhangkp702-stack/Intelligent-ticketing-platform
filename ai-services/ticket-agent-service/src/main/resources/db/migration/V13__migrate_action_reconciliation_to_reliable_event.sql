-- 把动作对账专用 Outbox/Inbox 迁移到 framework 通用可靠事件表。
CREATE TABLE t_reliable_outbox_event (
    namespace VARCHAR(64) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    deduplication_key VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    event_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    next_publish_at TIMESTAMP(3) NOT NULL,
    publish_owner VARCHAR(128),
    publish_lease_until TIMESTAMP(3),
    publish_fencing_token BIGINT NOT NULL DEFAULT 0,
    publish_attempt_count INT NOT NULL DEFAULT 0,
    broker_message_id VARCHAR(128),
    published_at TIMESTAMP(3),
    failure_category VARCHAR(64),
    failure_message VARCHAR(512),
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (namespace, event_id),
    CONSTRAINT uk_reliable_outbox_dedupe UNIQUE (namespace, deduplication_key)
);

CREATE INDEX idx_reliable_outbox_publish
    ON t_reliable_outbox_event (namespace, status, next_publish_at);
CREATE INDEX idx_reliable_outbox_lease
    ON t_reliable_outbox_event (namespace, status, publish_lease_until);

CREATE TABLE t_reliable_inbox_consumption (
    namespace VARCHAR(64) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    consumer_name VARCHAR(128) NOT NULL,
    event_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL,
    max_attempts INT NOT NULL,
    next_retry_at TIMESTAMP(3),
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP(3),
    fencing_token BIGINT NOT NULL DEFAULT 0,
    failure_category VARCHAR(64),
    failure_message VARCHAR(512),
    started_at TIMESTAMP(3),
    finished_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (namespace, event_id, consumer_name)
);

CREATE INDEX idx_reliable_inbox_lease
    ON t_reliable_inbox_consumption (namespace, consumer_name, status, lease_until);
CREATE INDEX idx_reliable_inbox_retry
    ON t_reliable_inbox_consumption (namespace, consumer_name, status, next_retry_at);

-- Outbox 只描述发布状态；旧表中的消费状态由下方 Inbox 独立承接。
INSERT INTO t_reliable_outbox_event (
    namespace, event_id, deduplication_key, event_type, aggregate_id, payload,
    event_version, status, next_publish_at, publish_fencing_token,
    publish_attempt_count, broker_message_id, published_at, failure_category,
    failure_message, created_at, updated_at
)
SELECT 'agent-action-reconciliation',
       id,
       action_id,
       'ACTION_RECONCILIATION_REQUESTED',
       action_id,
       action_id,
       event_version,
       CASE status
           WHEN 'PENDING' THEN 'PENDING'
           WHEN 'RETRY_WAIT' THEN 'PENDING'
           ELSE 'PUBLISHED'
       END,
       CASE WHEN status = 'RETRY_WAIT' THEN next_retry_at ELSE updated_at END,
       0,
       CASE WHEN mq_message_id IS NULL THEN 0 ELSE 1 END,
       mq_message_id,
       published_at,
       failure_category,
       failure_message,
       created_at,
       updated_at
FROM t_agent_action_reconciliation;

-- 只有已经开始或结束消费的旧事件需要创建 Inbox；尚未消费的事件等待第一条 MQ 消息时再创建。
INSERT INTO t_reliable_inbox_consumption (
    namespace, event_id, consumer_name, event_version, status, attempt_count,
    max_attempts, next_retry_at, lease_owner, lease_until, fencing_token,
    failure_category, failure_message, started_at, finished_at, created_at, updated_at
)
SELECT 'agent-action-reconciliation',
       id,
       COALESCE(consumer_name, 'agent-action-reconciliation-v1'),
       event_version,
       CASE status
           WHEN 'RUNNING' THEN 'PROCESSING'
           WHEN 'RETRY_WAIT' THEN 'RETRY_WAIT'
           WHEN 'SUCCEEDED' THEN 'SUCCEEDED'
           WHEN 'FAILED' THEN 'FAILED'
       END,
       CASE WHEN attempt_count > 0 THEN attempt_count ELSE 1 END,
       max_attempts,
       next_retry_at,
       CASE WHEN status = 'RUNNING' THEN lease_owner END,
       CASE WHEN status = 'RUNNING' THEN lease_until END,
       CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END,
       failure_category,
       failure_message,
       started_at,
       finished_at,
       created_at,
       updated_at
FROM t_agent_action_reconciliation
WHERE status IN ('RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED');

-- 旧版已经耗尽自动对账次数的记录直接显式转人工，避免迁移后留下无人调度的 UNKNOWN。
UPDATE t_agent_action_draft
SET status = 'MANUAL_REVIEW', failure_category = 'RECONCILIATION_EXHAUSTED', updated_at = CURRENT_TIMESTAMP(3)
WHERE id IN (SELECT action_id FROM t_agent_action_reconciliation WHERE status = 'FAILED')
  AND status IN ('UNKNOWN', 'RECONCILING');

UPDATE t_reliable_command
SET status = 'MANUAL_REVIEW', failure_category = 'RECONCILIATION_EXHAUSTED',
    failure_message = 'RECONCILIATION_EXHAUSTED', lease_owner = NULL,
    lease_until = NULL, next_reconcile_at = NULL, updated_at = CURRENT_TIMESTAMP(3)
WHERE namespace = 'agent-action-execution'
  AND command_id IN (SELECT action_id FROM t_agent_action_reconciliation WHERE status = 'FAILED')
  AND status IN ('UNKNOWN', 'RECONCILING');
