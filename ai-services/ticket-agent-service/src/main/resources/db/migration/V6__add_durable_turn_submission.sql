-- 轮次由服务端先预创建，首次提交时再绑定用户消息、参数指纹和在线执行租约。
ALTER TABLE t_agent_turn MODIFY COLUMN user_message_id VARCHAR(32) NULL;
ALTER TABLE t_agent_turn MODIFY COLUMN started_at TIMESTAMP(3) NULL;

ALTER TABLE t_agent_turn ADD COLUMN payload_hash VARCHAR(64);
ALTER TABLE t_agent_turn ADD COLUMN submission_expires_at TIMESTAMP(3);
ALTER TABLE t_agent_turn ADD COLUMN lease_owner VARCHAR(128);
ALTER TABLE t_agent_turn ADD COLUMN lease_until TIMESTAMP(3);
ALTER TABLE t_agent_turn ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0;
ALTER TABLE t_agent_turn ADD COLUMN last_heartbeat_at TIMESTAMP(3);

CREATE INDEX idx_agent_turn_status_lease
    ON t_agent_turn (status, lease_until, updated_at);
