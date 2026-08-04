-- 确认事务先落 QUEUED，执行器再通过租约和 fencing token 获得真实写权限。
ALTER TABLE t_agent_action_execution
    ADD COLUMN lease_owner VARCHAR(128);
ALTER TABLE t_agent_action_execution
    ADD COLUMN lease_until TIMESTAMP(3);
ALTER TABLE t_agent_action_execution
    ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0;
ALTER TABLE t_agent_action_execution
    ADD COLUMN last_heartbeat_at TIMESTAMP(3);
ALTER TABLE t_agent_action_execution
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE t_agent_action_execution
    ADD COLUMN next_reconcile_at TIMESTAMP(3);

CREATE INDEX idx_agent_action_execution_recovery
    ON t_agent_action_execution (outcome, lease_until, updated_at);
