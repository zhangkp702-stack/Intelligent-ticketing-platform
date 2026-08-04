/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.framework.starter.reliablecommand.jdbc;

import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandClaim;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandDefinition;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandMode;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStateMachine;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;
import org.opengoofy.index12306.framework.starter.reliablecommand.store.ReliableCommandAuditRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.store.ReliableCommandStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 使用服务本地数据源持久化可靠命令、租约和状态迁移审计。
 *
 * <p>内部事务采用 REQUIRED 语义。调用方已经开启本地事务时，命令记录会加入同一事务；没有外层事务时，
 * 单次状态迁移仍会独立提交。LOCAL_ATOMIC 模式的业务执行器必须在外层事务中组合认领、业务写入和成功状态。</p>
 */
public class JdbcReliableCommandStore implements ReliableCommandStore {

    private static final String COMMAND_COLUMNS = "routing_key, namespace, command_id, command_type, execution_mode, owner_id, "
            + "request_fingerprint, fingerprint_version, status, result_payload, failure_category, failure_message, "
            + "business_reference, lease_owner, lease_until, fencing_token, last_heartbeat_at, attempt_count, "
            + "next_reconcile_at, reconcile_attempt_count, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建基于当前数据源事务管理器的可靠命令存储。
     *
     * @param jdbcTemplate 当前服务或当前分片的 JDBC 访问器
     * @param transactionManager 与该数据源对应的事务管理器
     */
    public JdbcReliableCommandStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /**
     * 首次插入命令并获得执行租约，重复命令比较 owner、指纹和当前状态。
     *
     * @param definition 命令定义
     * @param leaseOwner 执行实例标识
     * @param namespace 只恢复指定命令域
     * @param now 当前时间
     * @param leaseUntil 租约截止时间
     * @return 认领或重复请求判定结果
     */
    @Override
    public ReliableCommandClaim claim(
            ReliableCommandDefinition definition,
            String leaseOwner,
            Instant now,
            Instant leaseUntil) {
        Objects.requireNonNull(definition, "definition");
        validateLeaseWindow(leaseOwner, now, leaseUntil);
        return requireResult(transactionTemplate.execute(status -> {
            try {
                // 复合主键是多实例竞争的最终裁决，只允许一个请求创建初始 PROCESSING 记录。
                insertInitialCommand(definition, leaseOwner.trim(), now, leaseUntil);
                ReliableCommandRecord acquired = requireRecord(definition.key());
                insertAudit(definition.key(), leaseOwner, null, ReliableCommandStatus.PROCESSING,
                        "COMMAND_CLAIMED", null, now);
                return new ReliableCommandClaim(ReliableCommandClaim.Outcome.ACQUIRED, acquired);
            } catch (DuplicateKeyException ignored) {
                // 首个事务提交后读取权威记录，再区分合法重放、冲突请求和未知结果。
                ReliableCommandRecord existing = requireRecord(definition.key());
                return classifyDuplicate(definition, existing);
            }
        }));
    }

    /**
     * 查询命令当前状态。
     *
     * @param key 命令键
     * @return 命令记录，不存在时为空
     */
    @Override
    public Optional<ReliableCommandRecord> find(ReliableCommandKey key) {
        Objects.requireNonNull(key, "key");
        return requireResult(transactionTemplate.execute(status -> findInternal(key)));
    }

    /**
     * 按命令域和稳定状态统计持久化记录，供业务侧暴露积压健康指标。
     *
     * @param namespace 命令业务域
     * @param status 待统计的可靠命令状态
     * @return 当前状态记录数
     */
    @Override
    public long countByStatus(String namespace, ReliableCommandStatus status) {
        String normalizedNamespace = requireText(namespace, "namespace", 64);
        Objects.requireNonNull(status, "status");
        // 查询命中现有 namespace + status 索引，不读取命令载荷或租约敏感字段。
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reliable_command WHERE namespace = ? AND status = ?",
                Long.class, normalizedNamespace, status.name());
        return count == null ? 0L : count;
    }

    /**
     * 使用状态、实例和围栏令牌共同约束心跳续租。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param heartbeatAt 心跳时间
     * @param leaseUntil 新租约截止时间
     * @return 续租成功返回 true
     */
    @Override
    public boolean heartbeat(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            Instant heartbeatAt,
            Instant leaseUntil) {
        validateLeaseUpdate(key, lease, heartbeatAt, leaseUntil);
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // PROCESSING 和 RECONCILING 都可能执行较慢的外部调用，但旧围栏令牌永远不能续租。
            int updated = jdbcTemplate.update(
                    "UPDATE t_reliable_command SET last_heartbeat_at = ?, lease_until = ?, updated_at = ? "
                            + "WHERE routing_key = ? AND namespace = ? AND command_id = ? "
                            + "AND lease_owner = ? AND fencing_token = ? "
                            + "AND status IN (?, ?)",
                    timestamp(heartbeatAt), timestamp(leaseUntil), timestamp(heartbeatAt),
                    key.routingKey(), key.namespace(), key.commandId(), lease.owner(), lease.fencingToken(),
                    ReliableCommandStatus.PROCESSING.name(), ReliableCommandStatus.RECONCILING.name());
            return updated == 1;
        }));
    }

    /**
     * 将当前真实业务执行标记为成功并持久化可重放结果。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param resultPayload 可复用的序列化结果
     * @param businessReference 安全业务引用
     * @param completedAt 完成时间
     * @return 状态成功迁移返回 true
     */
    @Override
    public boolean markSucceeded(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            String resultPayload,
            String businessReference,
            Instant completedAt) {
        validateTransitionArguments(key, lease, completedAt);
        return transitionProcessing(
                key, lease, ReliableCommandStatus.SUCCEEDED,
                resultPayload, businessReference, null, null, null,
                "COMMAND_SUCCEEDED", completedAt);
    }

    /**
     * 将可以确定没有未知远程副作用的执行标记为失败。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param failureCategory 稳定失败分类
     * @param failureMessage 限长故障摘要
     * @param completedAt 完成时间
     * @return 状态成功迁移返回 true
     */
    @Override
    public boolean markFailed(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            String failureCategory,
            String failureMessage,
            Instant completedAt) {
        validateTransitionArguments(key, lease, completedAt);
        return transitionProcessing(
                key, lease, ReliableCommandStatus.FAILED,
                null, null, failureCategory, failureMessage, null,
                "COMMAND_FAILED", completedAt);
    }

    /**
     * 将无法证明远程副作用未成功的执行转为未知状态。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param failureCategory 未知原因分类
     * @param failureMessage 限长故障摘要
     * @param nextReconcileAt 下一次权威查询时间
     * @param changedAt 状态迁移时间
     * @return 状态成功迁移返回 true
     */
    @Override
    public boolean markUnknown(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            String failureCategory,
            String failureMessage,
            Instant nextReconcileAt,
            Instant changedAt) {
        validateTransitionArguments(key, lease, changedAt);
        Objects.requireNonNull(nextReconcileAt, "nextReconcileAt");
        return transitionProcessing(
                key, lease, ReliableCommandStatus.UNKNOWN,
                null, null, failureCategory, failureMessage, nextReconcileAt,
                "COMMAND_RESULT_UNKNOWN", changedAt);
    }

    /**
     * 回收过期的真实执行和只读对账租约，统一回到 UNKNOWN 等待安全查询。
     *
     * @param now 当前时间
     * @param operatorId 恢复器实例标识
     * @param limit 单批处理上限
     * @return 成功恢复的记录数量
     */
    @Override
    public int recoverExpiredLeases(String namespace, Instant now, String operatorId, int limit) {
        String normalizedNamespace = requireText(namespace, "namespace", 64);
        Objects.requireNonNull(now, "now");
        String normalizedOperator = requireText(operatorId, "operatorId", 128);
        validateLimit(limit);
        return requireResult(transactionTemplate.execute(status -> {
            // 候选查询不持有长事务锁，每条更新再次匹配状态、租约和围栏令牌。
            List<ReliableCommandRecord> candidates = jdbcTemplate.query(
                    "SELECT " + COMMAND_COLUMNS + " FROM t_reliable_command "
                            + "WHERE namespace = ? AND status IN (?, ?) AND lease_until <= ? "
                            + "ORDER BY lease_until LIMIT ?",
                    this::mapCommand,
                    normalizedNamespace,
                    ReliableCommandStatus.PROCESSING.name(), ReliableCommandStatus.RECONCILING.name(),
                    timestamp(now), limit);
            int recovered = 0;
            for (ReliableCommandRecord candidate : candidates) {
                ReliableCommandStatus oldStatus = candidate.status();
                int updated = jdbcTemplate.update(
                        "UPDATE t_reliable_command SET status = ?, failure_category = ?, failure_message = ?, "
                                + "lease_owner = NULL, lease_until = NULL, next_reconcile_at = ?, updated_at = ? "
                                + "WHERE routing_key = ? AND namespace = ? AND command_id = ? "
                                + "AND status = ? AND lease_owner = ? "
                                + "AND fencing_token = ? AND lease_until = ?",
                        ReliableCommandStatus.UNKNOWN.name(), "LEASE_EXPIRED", "执行租约已过期",
                        timestamp(now), timestamp(now),
                        candidate.key().routingKey(), candidate.key().namespace(), candidate.key().commandId(),
                        oldStatus.name(),
                        candidate.leaseOwner(), candidate.fencingToken(), timestamp(candidate.leaseUntil()));
                if (updated == 1) {
                    insertAudit(candidate.key(), normalizedOperator, oldStatus, ReliableCommandStatus.UNKNOWN,
                            "LEASE_EXPIRED_TO_UNKNOWN", null, now);
                    recovered++;
                }
            }
            return recovered;
        }));
    }

    /**
     * 查询已经到达对账时间的未知命令。
     *
     * @param namespace 只查询指定命令域
     * @param now 当前时间
     * @param limit 单批查询上限
     * @return 待对账命令
     */
    @Override
    public List<ReliableCommandRecord> findDueReconciliations(String namespace, Instant now, int limit) {
        String normalizedNamespace = requireText(namespace, "namespace", 64);
        Objects.requireNonNull(now, "now");
        validateLimit(limit);
        return requireResult(transactionTemplate.execute(status -> jdbcTemplate.query(
                "SELECT " + COMMAND_COLUMNS + " FROM t_reliable_command "
                        + "WHERE namespace = ? AND status = ? "
                        + "AND (next_reconcile_at IS NULL OR next_reconcile_at <= ?) "
                        + "ORDER BY next_reconcile_at, created_at LIMIT ?",
                this::mapCommand, normalizedNamespace, ReliableCommandStatus.UNKNOWN.name(), timestamp(now), limit)));
    }

    /**
     * 原子领取未知命令进入只读对账并生成新的围栏令牌。
     *
     * @param key 命令键
     * @param operatorId 对账实例标识
     * @param now 当前时间
     * @param leaseUntil 对账租约截止时间
     * @return 领取后的记录，竞争失败时为空
     */
    @Override
    public Optional<ReliableCommandRecord> claimReconciliation(
            ReliableCommandKey key,
            String operatorId,
            Instant now,
            Instant leaseUntil) {
        Objects.requireNonNull(key, "key");
        validateLeaseWindow(operatorId, now, leaseUntil);
        String normalizedOperator = operatorId.trim();
        return requireResult(transactionTemplate.execute(status -> {
            // 只有到期 UNKNOWN 可以进入对账；真实业务 PROCESSING 永远不会被直接重新执行。
            int updated = jdbcTemplate.update(
                    "UPDATE t_reliable_command SET status = ?, lease_owner = ?, lease_until = ?, "
                            + "last_heartbeat_at = ?, fencing_token = fencing_token + 1, "
                            + "reconcile_attempt_count = reconcile_attempt_count + 1, updated_at = ? "
                            + "WHERE routing_key = ? AND namespace = ? AND command_id = ? AND status = ? "
                            + "AND (next_reconcile_at IS NULL OR next_reconcile_at <= ?)",
                    ReliableCommandStatus.RECONCILING.name(), normalizedOperator, timestamp(leaseUntil),
                    timestamp(now), timestamp(now), key.routingKey(), key.namespace(), key.commandId(),
                    ReliableCommandStatus.UNKNOWN.name(), timestamp(now));
            if (updated != 1) {
                return Optional.empty();
            }
            insertAudit(key, normalizedOperator, ReliableCommandStatus.UNKNOWN,
                    ReliableCommandStatus.RECONCILING, "RECONCILIATION_CLAIMED", null, now);
            return findInternal(key);
        }));
    }

    /**
     * 使用权威下游成功事实结束对账。
     *
     * @param key 命令键
     * @param lease 当前对账租约
     * @param resultPayload 可复用结果
     * @param businessReference 安全业务引用
     * @param evidence 权威查询证据摘要
     * @param completedAt 完成时间
     * @return 状态成功迁移返回 true
     */
    @Override
    public boolean reconcileSucceeded(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            String resultPayload,
            String businessReference,
            String evidence,
            Instant completedAt) {
        validateTransitionArguments(key, lease, completedAt);
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 对账完成仍需围栏匹配，过期查询的迟到结果不能覆盖新一轮对账。
            int updated = jdbcTemplate.update(
                    "UPDATE t_reliable_command SET status = ?, result_payload = ?, "
                            + "business_reference = COALESCE(?, business_reference), "
                            + "failure_category = NULL, failure_message = NULL, lease_owner = NULL, lease_until = NULL, "
                            + "next_reconcile_at = NULL, updated_at = ? "
                            + "WHERE routing_key = ? AND namespace = ? AND command_id = ? AND status = ? "
                            + "AND lease_owner = ? AND fencing_token = ?",
                    ReliableCommandStatus.SUCCEEDED.name(), resultPayload, normalizeOptional(businessReference, 256),
                    timestamp(completedAt), key.routingKey(), key.namespace(), key.commandId(),
                    ReliableCommandStatus.RECONCILING.name(), lease.owner(), lease.fencingToken());
            if (updated == 1) {
                insertAudit(key, lease.owner(), ReliableCommandStatus.RECONCILING,
                        ReliableCommandStatus.SUCCEEDED, "RECONCILIATION_CONFIRMED_SUCCESS",
                        truncateOptional(evidence, 512), completedAt);
            }
            return updated == 1;
        }));
    }

    /**
     * 使用权威下游失败事实结束对账，失败终态仍受当前对账围栏保护。
     *
     * @param key 命令键
     * @param lease 当前对账租约
     * @param failureCategory 稳定失败分类
     * @param failureMessage 限长失败摘要
     * @param evidence 权威查询证据摘要
     * @param completedAt 完成时间
     * @return 状态成功迁移返回 true
     */
    @Override
    public boolean reconcileFailed(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            String failureCategory,
            String failureMessage,
            String evidence,
            Instant completedAt) {
        validateTransitionArguments(key, lease, completedAt);
        ReliableCommandStateMachine.requireTransition(
                ReliableCommandStatus.RECONCILING, ReliableCommandStatus.FAILED);
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 只有当前对账租约可以提交权威失败，迟到查询不能覆盖后续状态。
            int updated = jdbcTemplate.update(
                    "UPDATE t_reliable_command SET status = ?, failure_category = ?, failure_message = ?, "
                            + "lease_owner = NULL, lease_until = NULL, next_reconcile_at = NULL, updated_at = ? "
                            + "WHERE routing_key = ? AND namespace = ? AND command_id = ? AND status = ? "
                            + "AND lease_owner = ? AND fencing_token = ?",
                    ReliableCommandStatus.FAILED.name(), normalizeOptional(failureCategory, 64),
                    truncateOptional(failureMessage, 512), timestamp(completedAt),
                    key.routingKey(), key.namespace(), key.commandId(),
                    ReliableCommandStatus.RECONCILING.name(), lease.owner(), lease.fencingToken());
            if (updated == 1) {
                insertAudit(key, lease.owner(), ReliableCommandStatus.RECONCILING,
                        ReliableCommandStatus.FAILED, "RECONCILIATION_CONFIRMED_FAILURE",
                        truncateOptional(evidence, 512), completedAt);
            }
            return updated == 1;
        }));
    }

    /**
     * 在权威查询仍无结论时安排下一次查询或转人工处理。
     *
     * @param key 命令键
     * @param lease 当前对账租约
     * @param targetStatus 只允许 UNKNOWN 或 MANUAL_REVIEW
     * @param failureCategory 稳定原因分类
     * @param failureMessage 限长原因摘要
     * @param evidence 权威查询证据摘要
     * @param nextReconcileAt 下一次查询时间，转人工时为空
     * @param changedAt 状态迁移时间
     * @return 状态成功迁移返回 true
     */
    @Override
    public boolean finishReconciliation(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            ReliableCommandStatus targetStatus,
            String failureCategory,
            String failureMessage,
            String evidence,
            Instant nextReconcileAt,
            Instant changedAt) {
        validateTransitionArguments(key, lease, changedAt);
        if (targetStatus != ReliableCommandStatus.UNKNOWN
                && targetStatus != ReliableCommandStatus.MANUAL_REVIEW) {
            throw new IllegalArgumentException("targetStatus must be UNKNOWN or MANUAL_REVIEW");
        }
        if (targetStatus == ReliableCommandStatus.UNKNOWN && nextReconcileAt == null) {
            throw new IllegalArgumentException("nextReconcileAt is required for UNKNOWN");
        }
        if (targetStatus == ReliableCommandStatus.MANUAL_REVIEW && nextReconcileAt != null) {
            throw new IllegalArgumentException("nextReconcileAt must be null for MANUAL_REVIEW");
        }
        ReliableCommandStateMachine.requireTransition(ReliableCommandStatus.RECONCILING, targetStatus);
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 释放对账租约前持久化下一步安排，避免记录停留在无调度信息的未知状态。
            int updated = jdbcTemplate.update(
                    "UPDATE t_reliable_command SET status = ?, failure_category = ?, failure_message = ?, "
                            + "lease_owner = NULL, lease_until = NULL, next_reconcile_at = ?, updated_at = ? "
                            + "WHERE routing_key = ? AND namespace = ? AND command_id = ? AND status = ? "
                            + "AND lease_owner = ? AND fencing_token = ?",
                    targetStatus.name(), normalizeOptional(failureCategory, 64), truncateOptional(failureMessage, 512),
                    timestampOrNull(nextReconcileAt), timestamp(changedAt),
                    key.routingKey(), key.namespace(), key.commandId(),
                    ReliableCommandStatus.RECONCILING.name(), lease.owner(), lease.fencingToken());
            if (updated == 1) {
                insertAudit(key, lease.owner(), ReliableCommandStatus.RECONCILING, targetStatus,
                        targetStatus == ReliableCommandStatus.UNKNOWN
                                ? "RECONCILIATION_PENDING" : "RECONCILIATION_MANUAL_REVIEW",
                        truncateOptional(evidence, 512), changedAt);
            }
            return updated == 1;
        }));
    }

    /**
     * 将人工复核中的命令重新安排为只读对账，并记录操作员与原因审计。
     *
     * @param key 命令键
     * @param operatorId 人工操作员标识
     * @param reason 人工重新核对原因
     * @param nextReconcileAt 下一次权威查询时间
     * @param changedAt 状态迁移时间
     * @return 成功从 MANUAL_REVIEW 迁移到 UNKNOWN 时返回 true
     */
    @Override
    public boolean resumeManualReview(
            ReliableCommandKey key,
            String operatorId,
            String reason,
            Instant nextReconcileAt,
            Instant changedAt) {
        Objects.requireNonNull(key, "key");
        String normalizedOperator = requireText(operatorId, "operatorId", 128);
        String normalizedReason = requireText(reason, "reason", 512);
        Objects.requireNonNull(nextReconcileAt, "nextReconcileAt");
        Objects.requireNonNull(changedAt, "changedAt");
        ReliableCommandStateMachine.requireTransition(
                ReliableCommandStatus.MANUAL_REVIEW, ReliableCommandStatus.UNKNOWN);
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 人工只能恢复权威查询调度，清除旧租约但不改变原始请求指纹和业务命令定义。
            int updated = jdbcTemplate.update(
                    "UPDATE t_reliable_command SET status = ?, failure_category = ?, failure_message = ?, "
                            + "lease_owner = NULL, lease_until = NULL, next_reconcile_at = ?, updated_at = ? "
                            + "WHERE routing_key = ? AND namespace = ? AND command_id = ? AND status = ?",
                    ReliableCommandStatus.UNKNOWN.name(), "MANUAL_RECONCILIATION_REQUEUED", normalizedReason,
                    timestamp(nextReconcileAt), timestamp(changedAt),
                    key.routingKey(), key.namespace(), key.commandId(), ReliableCommandStatus.MANUAL_REVIEW.name());
            if (updated == 1) {
                // 审计明确记录操作员，后续终态仍必须由下游权威事实收口。
                insertAudit(key, normalizedOperator, ReliableCommandStatus.MANUAL_REVIEW,
                        ReliableCommandStatus.UNKNOWN, "MANUAL_RECONCILIATION_REQUEUED",
                        normalizedReason, changedAt);
            }
            return updated == 1;
        }));
    }

    /**
     * 查询命令的状态迁移审计。
     *
     * @param key 命令键
     * @return 按主键顺序排列的审计记录
     */
    @Override
    public List<ReliableCommandAuditRecord> findAudit(ReliableCommandKey key) {
        Objects.requireNonNull(key, "key");
        return requireResult(transactionTemplate.execute(status -> jdbcTemplate.query(
                "SELECT id, routing_key, namespace, command_id, operator_id, old_status, new_status, reason, "
                        + "evidence, created_at FROM t_reliable_command_audit "
                        + "WHERE routing_key = ? AND namespace = ? AND command_id = ? ORDER BY id",
                this::mapAudit, key.routingKey(), key.namespace(), key.commandId())));
    }

    /**
     * 插入首次命令记录。
     *
     * @param definition 命令定义
     * @param leaseOwner 执行实例标识
     * @param now 当前时间
     * @param leaseUntil 租约截止时间
     */
    private void insertInitialCommand(
            ReliableCommandDefinition definition,
            String leaseOwner,
            Instant now,
            Instant leaseUntil) {
        jdbcTemplate.update(
                "INSERT INTO t_reliable_command (" + COMMAND_COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, 1, ?, 1, NULL, 0, ?, ?)",
                definition.key().routingKey(), definition.key().namespace(), definition.key().commandId(),
                definition.commandType(), definition.mode().name(),
                definition.ownerId(), definition.requestFingerprint(), definition.fingerprintVersion(),
                ReliableCommandStatus.PROCESSING.name(), definition.businessReference(), leaseOwner,
                timestamp(leaseUntil), timestamp(now), timestamp(now), timestamp(now));
    }

    /**
     * 根据持久化记录分类重复请求。
     *
     * @param definition 当前请求定义
     * @param existing 已存在记录
     * @return 重复请求判定
     */
    private ReliableCommandClaim classifyDuplicate(
            ReliableCommandDefinition definition,
            ReliableCommandRecord existing) {
        ReliableCommandClaim.Outcome outcome;
        if (!Objects.equals(definition.ownerId(), existing.ownerId())) {
            outcome = ReliableCommandClaim.Outcome.OWNER_MISMATCH;
        } else if (!Objects.equals(definition.commandType(), existing.commandType())
                || !Objects.equals(definition.requestFingerprint(), existing.requestFingerprint())
                || !Objects.equals(definition.fingerprintVersion(), existing.fingerprintVersion())
                || definition.mode() != existing.mode()) {
            outcome = ReliableCommandClaim.Outcome.PAYLOAD_MISMATCH;
        } else {
            outcome = switch (existing.status()) {
                case SUCCEEDED -> ReliableCommandClaim.Outcome.REPLAY_SUCCEEDED;
                case PROCESSING -> ReliableCommandClaim.Outcome.PROCESSING;
                case FAILED -> ReliableCommandClaim.Outcome.TERMINAL_FAILURE;
                case UNKNOWN, RECONCILING, MANUAL_REVIEW -> ReliableCommandClaim.Outcome.RESULT_UNCERTAIN;
            };
        }
        return new ReliableCommandClaim(outcome, existing);
    }

    /**
     * 从 PROCESSING 执行带围栏条件的终态或未知状态迁移。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param targetStatus 目标状态
     * @param resultPayload 可复用结果
     * @param businessReference 安全业务引用
     * @param failureCategory 失败分类
     * @param failureMessage 故障摘要
     * @param nextReconcileAt 下一次对账时间
     * @param reason 审计原因
     * @param changedAt 状态迁移时间
     * @return 状态成功迁移返回 true
     */
    private boolean transitionProcessing(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            ReliableCommandStatus targetStatus,
            String resultPayload,
            String businessReference,
            String failureCategory,
            String failureMessage,
            Instant nextReconcileAt,
            String reason,
            Instant changedAt) {
        ReliableCommandStateMachine.requireTransition(ReliableCommandStatus.PROCESSING, targetStatus);
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 状态、租约 owner 和 fencing token 三者同时匹配才允许写入最终结果。
            int updated = jdbcTemplate.update(
                    "UPDATE t_reliable_command SET status = ?, result_payload = ?, "
                            + "business_reference = COALESCE(?, business_reference), "
                            + "failure_category = ?, failure_message = ?, lease_owner = NULL, lease_until = NULL, "
                            + "next_reconcile_at = ?, updated_at = ? "
                            + "WHERE routing_key = ? AND namespace = ? AND command_id = ? AND status = ? "
                            + "AND lease_owner = ? AND fencing_token = ?",
                    targetStatus.name(), resultPayload, normalizeOptional(businessReference, 256),
                    normalizeOptional(failureCategory, 64), truncateOptional(failureMessage, 512),
                    timestampOrNull(nextReconcileAt), timestamp(changedAt),
                    key.routingKey(), key.namespace(), key.commandId(),
                    ReliableCommandStatus.PROCESSING.name(), lease.owner(), lease.fencingToken());
            if (updated == 1) {
                insertAudit(key, lease.owner(), ReliableCommandStatus.PROCESSING, targetStatus,
                        reason, null, changedAt);
            }
            return updated == 1;
        }));
    }

    /**
     * 查询一条命令记录。
     *
     * @param key 命令键
     * @return 命令记录，不存在时为空
     */
    private Optional<ReliableCommandRecord> findInternal(ReliableCommandKey key) {
        List<ReliableCommandRecord> records = jdbcTemplate.query(
                "SELECT " + COMMAND_COLUMNS + " FROM t_reliable_command "
                        + "WHERE routing_key = ? AND namespace = ? AND command_id = ?",
                this::mapCommand, key.routingKey(), key.namespace(), key.commandId());
        return records.stream().findFirst();
    }

    /**
     * 查询必须存在的命令记录。
     *
     * @param key 命令键
     * @return 命令记录
     */
    private ReliableCommandRecord requireRecord(ReliableCommandKey key) {
        return findInternal(key).orElseThrow(
                () -> new IllegalStateException("Reliable command disappeared after claim: " + key));
    }

    /**
     * 写入不包含原始请求和结果正文的状态迁移审计。
     *
     * @param key 命令键
     * @param operatorId 操作实例
     * @param oldStatus 原状态
     * @param newStatus 新状态
     * @param reason 稳定迁移原因
     * @param evidence 安全证据摘要
     * @param createdAt 创建时间
     */
    private void insertAudit(
            ReliableCommandKey key,
            String operatorId,
            ReliableCommandStatus oldStatus,
            ReliableCommandStatus newStatus,
            String reason,
            String evidence,
            Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO t_reliable_command_audit "
                        + "(routing_key, namespace, command_id, operator_id, old_status, new_status, reason, "
                        + "evidence, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                key.routingKey(), key.namespace(), key.commandId(), requireText(operatorId, "operatorId", 128),
                oldStatus == null ? null : oldStatus.name(), newStatus.name(),
                requireText(reason, "reason", 128), truncateOptional(evidence, 512), timestamp(createdAt));
    }

    /**
     * 将命令查询结果映射为领域只读视图。
     *
     * @param resultSet JDBC 结果集
     * @param rowNum 行号
     * @return 命令记录
     * @throws SQLException 字段读取失败
     */
    private ReliableCommandRecord mapCommand(ResultSet resultSet, int rowNum) throws SQLException {
        return new ReliableCommandRecord(
                new ReliableCommandKey(
                        resultSet.getString("namespace"),
                        resultSet.getString("command_id"),
                        resultSet.getString("routing_key")),
                resultSet.getString("command_type"),
                ReliableCommandMode.valueOf(resultSet.getString("execution_mode")),
                resultSet.getString("owner_id"),
                resultSet.getString("request_fingerprint"),
                resultSet.getString("fingerprint_version"),
                ReliableCommandStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("result_payload"),
                resultSet.getString("failure_category"),
                resultSet.getString("failure_message"),
                resultSet.getString("business_reference"),
                resultSet.getString("lease_owner"),
                instant(resultSet.getTimestamp("lease_until")),
                resultSet.getLong("fencing_token"),
                instant(resultSet.getTimestamp("last_heartbeat_at")),
                resultSet.getInt("attempt_count"),
                instant(resultSet.getTimestamp("next_reconcile_at")),
                resultSet.getInt("reconcile_attempt_count"),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at")));
    }

    /**
     * 将审计查询结果映射为安全审计视图。
     *
     * @param resultSet JDBC 结果集
     * @param rowNum 行号
     * @return 审计记录
     * @throws SQLException 字段读取失败
     */
    private ReliableCommandAuditRecord mapAudit(ResultSet resultSet, int rowNum) throws SQLException {
        String oldStatus = resultSet.getString("old_status");
        return new ReliableCommandAuditRecord(
                resultSet.getLong("id"),
                new ReliableCommandKey(
                        resultSet.getString("namespace"),
                        resultSet.getString("command_id"),
                        resultSet.getString("routing_key")),
                resultSet.getString("operator_id"),
                oldStatus == null ? null : ReliableCommandStatus.valueOf(oldStatus),
                ReliableCommandStatus.valueOf(resultSet.getString("new_status")),
                resultSet.getString("reason"),
                resultSet.getString("evidence"),
                instant(resultSet.getTimestamp("created_at")));
    }

    /**
     * 校验租约时间窗口和实例标识。
     *
     * @param owner 租约实例
     * @param now 当前时间
     * @param leaseUntil 截止时间
     */
    private void validateLeaseWindow(String owner, Instant now, Instant leaseUntil) {
        requireText(owner, "leaseOwner", 128);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
    }

    /**
     * 校验心跳更新参数。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param heartbeatAt 心跳时间
     * @param leaseUntil 新截止时间
     */
    private void validateLeaseUpdate(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            Instant heartbeatAt,
            Instant leaseUntil) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(lease, "lease");
        validateLeaseWindow(lease.owner(), heartbeatAt, leaseUntil);
    }

    /**
     * 校验带围栏状态迁移参数。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param changedAt 迁移时间
     */
    private void validateTransitionArguments(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            Instant changedAt) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(changedAt, "changedAt");
    }

    /**
     * 校验批处理上限，避免错误配置造成无界数据库扫描。
     *
     * @param limit 单批数量
     */
    private void validateLimit(int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
    }

    /**
     * 校验必填文本并限制持久化长度。
     *
     * @param value 原始值
     * @param fieldName 字段名称
     * @param maxLength 最大长度
     * @return 规范化文本
     */
    private String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    /**
     * 规范化允许为空的文本并限制持久化长度。
     *
     * @param value 原始值
     * @param maxLength 最大长度
     * @return 规范化文本，空白值返回 null
     */
    private String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("text exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    /**
     * 规范化并安全截断异常摘要或审计证据。
     *
     * @param value 原始文本
     * @param maxLength 最大持久化长度
     * @return 截断后的文本，空白值返回 null
     */
    private String truncateOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    /**
     * 将非空时间转换为 JDBC 时间戳。
     *
     * @param value 时间
     * @return JDBC 时间戳
     */
    private Timestamp timestamp(Instant value) {
        return Timestamp.from(Objects.requireNonNull(value, "instant"));
    }

    /**
     * 将可空时间转换为 JDBC 时间戳。
     *
     * @param value 可空时间
     * @return JDBC 时间戳或 null
     */
    private Timestamp timestampOrNull(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /**
     * 将可空 JDBC 时间戳转换为 Instant。
     *
     * @param value JDBC 时间戳
     * @return Instant 或 null
     */
    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * 校验事务回调必须返回结果。
     *
     * @param result 事务回调结果
     * @param <T> 结果类型
     * @return 非空结果
     */
    private <T> T requireResult(T result) {
        return Objects.requireNonNull(result, "transaction result");
    }
}
