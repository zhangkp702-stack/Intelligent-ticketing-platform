-- 使用通用可靠命令表替代 Agent 自维护的动作执行租约和状态机。
CREATE TABLE t_reliable_command (
    routing_key VARCHAR(128) NOT NULL,
    namespace VARCHAR(64) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    execution_mode VARCHAR(32) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    fingerprint_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_payload TEXT,
    failure_category VARCHAR(64),
    failure_message VARCHAR(512),
    business_reference VARCHAR(256),
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP(3),
    fencing_token BIGINT NOT NULL DEFAULT 1,
    last_heartbeat_at TIMESTAMP(3),
    attempt_count INT NOT NULL DEFAULT 1,
    next_reconcile_at TIMESTAMP(3),
    reconcile_attempt_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (routing_key, namespace, command_id)
);

CREATE INDEX idx_reliable_command_reconcile
    ON t_reliable_command (namespace, status, next_reconcile_at);
CREATE INDEX idx_reliable_command_lease
    ON t_reliable_command (namespace, status, lease_until);
CREATE INDEX idx_reliable_command_owner
    ON t_reliable_command (owner_id, created_at);

CREATE TABLE t_reliable_command_audit (
    id BIGINT AUTO_INCREMENT NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    namespace VARCHAR(64) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    old_status VARCHAR(32),
    new_status VARCHAR(32) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    evidence VARCHAR(512),
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_reliable_command_audit_key
    ON t_reliable_command_audit (routing_key, namespace, command_id, id);

-- 历史 executionId 只作为迁移来源；新命令统一以服务端 actionId 作为真实写身份。
INSERT INTO t_reliable_command (
    routing_key, namespace, command_id, command_type, execution_mode,
    owner_id, request_fingerprint, fingerprint_version, status, result_payload,
    failure_category, failure_message, business_reference, lease_owner, lease_until,
    fencing_token, last_heartbeat_at, attempt_count, next_reconcile_at,
    reconcile_attempt_count, created_at, updated_at
)
SELECT execution.action_id,
       'agent-action-execution',
       execution.action_id,
       draft.action_type,
       'REMOTE_EFFECT',
       draft.user_id,
       draft.payload_hash,
       'agent-action-v1',
       CASE execution.outcome
           WHEN 'QUEUED' THEN 'PROCESSING'
           WHEN 'STARTED' THEN 'PROCESSING'
           WHEN 'SUCCEEDED' THEN 'SUCCEEDED'
           WHEN 'FAILED' THEN 'FAILED'
           WHEN 'UNKNOWN' THEN 'UNKNOWN'
           -- 旧对账租约保存在 Outbox/Inbox 表中，执行记录自身没有可迁移租约，需从 UNKNOWN 重新领取只读对账权。
           WHEN 'RECONCILING' THEN 'UNKNOWN'
       END,
       draft.result_json,
       execution.failure_category,
       execution.exception_type,
       COALESCE(execution.result_reference, execution.action_id),
       CASE WHEN execution.outcome IN ('QUEUED', 'STARTED')
           THEN COALESCE(execution.lease_owner, 'legacy-action-migration') END,
       CASE WHEN execution.outcome IN ('QUEUED', 'STARTED')
           THEN COALESCE(execution.lease_until, execution.updated_at) END,
       CASE WHEN execution.fencing_token > 0 THEN execution.fencing_token ELSE 1 END,
       execution.last_heartbeat_at,
       CASE WHEN execution.attempt_count > 0 THEN execution.attempt_count ELSE 1 END,
       execution.next_reconcile_at,
       0,
       execution.created_at,
       execution.updated_at
FROM t_agent_action_execution execution
JOIN t_agent_action_draft draft ON draft.id = execution.action_id;
