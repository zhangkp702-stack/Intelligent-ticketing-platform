-- 保存恢复执行所需的用户名，并为每个服务端任务建立受 Turn 围栏保护的持久化检查点。
ALTER TABLE t_agent_turn ADD COLUMN username VARCHAR(64);

CREATE TABLE t_agent_task_execution (
    id VARCHAR(32) NOT NULL,
    turn_id VARCHAR(32) NOT NULL,
    sequence_no INT NOT NULL,
    intent VARCHAR(64) NOT NULL,
    plan_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    failure_category VARCHAR(64),
    started_at TIMESTAMP(3),
    finished_at TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_task_turn_sequence UNIQUE (turn_id, sequence_no)
);

CREATE INDEX idx_agent_task_turn_status
    ON t_agent_task_execution (turn_id, status, sequence_no);
