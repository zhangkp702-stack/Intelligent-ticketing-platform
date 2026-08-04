package org.opengoofy.index12306.ai.agentservice.action;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证旧动作对账记录迁移到通用 Outbox/Inbox 后不会丢失运行态和人工处理状态。
 */
class ActionReliableEventMigrationTests {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    /**
     * 构造 V12 旧数据并执行 V13，验证运行中消费和耗尽任务的状态映射。
     */
    @Test
    void shouldMigrateLegacyReconciliationState() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:action_migration_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");

        // 先停在 V12，模拟升级前已经存在的专用动作对账数据。
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("12").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertAction(jdbc, "action-running", "RECONCILING");
        insertAction(jdbc, "action-failed", "UNKNOWN");
        insertCommand(jdbc, "action-running");
        insertCommand(jdbc, "action-failed");
        insertLegacyEvent(jdbc, "event-running", "action-running", "RUNNING", 2, "worker-old");
        insertLegacyEvent(jdbc, "event-failed", "action-failed", "FAILED", 5, null);

        // 继续执行 V13，真实验证 SQL 回填和终态同步，而不是只验证空表建表。
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        Map<String, Object> runningOutbox = jdbc.queryForMap(
                "SELECT status, aggregate_id FROM t_reliable_outbox_event WHERE event_id = ?",
                "event-running");
        Map<String, Object> runningInbox = jdbc.queryForMap(
                "SELECT status, attempt_count, lease_owner FROM t_reliable_inbox_consumption WHERE event_id = ?",
                "event-running");
        assertThat(runningOutbox).containsEntry("status", "PUBLISHED")
                .containsEntry("aggregate_id", "action-running");
        assertThat(runningInbox).containsEntry("status", "PROCESSING")
                .containsEntry("attempt_count", 2)
                .containsEntry("lease_owner", "worker-old");

        // 旧版已耗尽记录必须同时收口 Inbox、动作草案和可靠命令，不能继续显示 UNKNOWN。
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_reliable_inbox_consumption WHERE event_id = ?",
                String.class, "event-failed")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_agent_action_draft WHERE id = ?",
                String.class, "action-failed")).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_reliable_command WHERE command_id = ?",
                String.class, "action-failed")).isEqualTo("MANUAL_REVIEW");
    }

    /**
     * 插入迁移测试所需的最小动作草案。
     *
     * @param jdbc JDBC 访问器
     * @param actionId 动作标识
     * @param status 旧动作状态
     */
    private void insertAction(JdbcTemplate jdbc, String actionId, String status) {
        jdbc.update(
                "INSERT INTO t_agent_action_draft "
                        + "(id, user_id, conversation_id, turn_id, action_type, status, payload_json, payload_hash, "
                        + "confirmation_expires_at, execution_id, version, created_at, updated_at) "
                        + "VALUES (?, 'user-1', 'conversation-1', ?, 'TICKET_PURCHASE', ?, '{}', ?, ?, ?, 0, ?, ?)",
                actionId, actionId, status, "hash-" + actionId,
                timestamp(NOW.plusSeconds(3600)), actionId, timestamp(NOW), timestamp(NOW));
    }

    /**
     * 插入 V12 已存在的 UNKNOWN 可靠命令。
     *
     * @param jdbc JDBC 访问器
     * @param actionId 动作标识
     */
    private void insertCommand(JdbcTemplate jdbc, String actionId) {
        jdbc.update(
                "INSERT INTO t_reliable_command "
                        + "(routing_key, namespace, command_id, command_type, execution_mode, owner_id, "
                        + "request_fingerprint, fingerprint_version, status, fencing_token, attempt_count, "
                        + "reconcile_attempt_count, created_at, updated_at) "
                        + "VALUES (?, 'agent-action-execution', ?, 'TICKET_PURCHASE', 'REMOTE_EFFECT', 'user-1', "
                        + "?, 'agent-action-v1', 'UNKNOWN', 1, 1, 0, ?, ?)",
                actionId, actionId, "hash-" + actionId, timestamp(NOW), timestamp(NOW));
    }

    /**
     * 插入旧版合并 Outbox/Inbox 对账记录。
     *
     * @param jdbc JDBC 访问器
     * @param eventId 事件标识
     * @param actionId 动作标识
     * @param status 旧对账状态
     * @param attemptCount 已处理次数
     * @param leaseOwner 旧消费实例
     */
    private void insertLegacyEvent(
            JdbcTemplate jdbc,
            String eventId,
            String actionId,
            String status,
            int attemptCount,
            String leaseOwner) {
        jdbc.update(
                "INSERT INTO t_agent_action_reconciliation "
                        + "(id, action_id, event_version, status, attempt_count, max_attempts, lease_owner, "
                        + "lease_until, consumer_name, mq_message_id, published_at, started_at, finished_at, "
                        + "version, created_at, updated_at) "
                        + "VALUES (?, ?, 1, ?, ?, 5, ?, ?, 'agent-action-reconciliation-v1', 'mq-old', ?, ?, ?, 0, ?, ?)",
                eventId, actionId, status, attemptCount, leaseOwner,
                leaseOwner == null ? null : timestamp(NOW.plusSeconds(30)), timestamp(NOW),
                timestamp(NOW), "FAILED".equals(status) ? timestamp(NOW.plusSeconds(1)) : null,
                timestamp(NOW), timestamp(NOW));
    }

    /**
     * 转换测试时间为 JDBC 时间戳。
     *
     * @param instant 时间
     * @return JDBC 时间戳
     */
    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
