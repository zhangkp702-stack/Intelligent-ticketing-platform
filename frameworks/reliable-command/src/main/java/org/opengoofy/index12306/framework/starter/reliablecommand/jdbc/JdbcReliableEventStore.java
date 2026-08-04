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

import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableConsumptionResult;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventDefinition;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxStatus;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于业务服务本地数据库实现可靠 Outbox 和 Inbox，所有状态迁移均使用租约围栏。
 */
public class JdbcReliableEventStore implements ReliableEventStore {

    private static final String OUTBOX_COLUMNS = "namespace, event_id, deduplication_key, event_type, "
            + "aggregate_id, payload, event_version, status, next_publish_at, publish_owner, "
            + "publish_lease_until, publish_fencing_token, publish_attempt_count, broker_message_id, "
            + "published_at, failure_category, failure_message, created_at, updated_at";
    private static final String INBOX_COLUMNS = "namespace, event_id, consumer_name, event_version, status, "
            + "attempt_count, max_attempts, next_retry_at, lease_owner, lease_until, fencing_token, "
            + "failure_category, failure_message, started_at, finished_at, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建共享当前服务数据源和本地事务的可靠事件存储。
     *
     * @param jdbcTemplate JDBC 访问器
     * @param transactionManager 当前服务事务管理器
     */
    public JdbcReliableEventStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /**
     * 幂等写入 Outbox，并校验重复去重键没有绑定不同载荷。
     *
     * @param definition 事件定义
     * @param now 创建时间
     * @return 新建或既有事件
     */
    @Override
    public ReliableOutboxRecord enqueue(ReliableEventDefinition definition, Instant now) {
        ReliableEventDefinition normalized = validateDefinition(definition);
        Objects.requireNonNull(now, "now");
        return requireResult(transactionTemplate.execute(status -> {
            // 先复用既有业务去重键；数据库唯一约束负责处理跨实例并发插入。
            Optional<ReliableOutboxRecord> existing = findByDeduplicationKeyInternal(
                    normalized.key().namespace(), normalized.deduplicationKey());
            if (existing.isPresent()) {
                assertSameDefinition(existing.get(), normalized);
                return existing.get();
            }
            jdbcTemplate.update(
                    "INSERT IGNORE INTO t_reliable_outbox_event "
                            + "(namespace, event_id, deduplication_key, event_type, aggregate_id, payload, "
                            + "event_version, status, next_publish_at, publish_fencing_token, "
                            + "publish_attempt_count, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)",
                    normalized.key().namespace(), normalized.key().eventId(),
                    normalized.deduplicationKey(), normalized.eventType(), normalized.aggregateId(),
                    normalized.payload(), normalized.eventVersion(), ReliableOutboxStatus.PENDING.name(),
                    timestamp(now), timestamp(now), timestamp(now));
            ReliableOutboxRecord saved = findByDeduplicationKeyForUpdate(
                    normalized.key().namespace(), normalized.deduplicationKey())
                    .orElseThrow(() -> new IllegalStateException("事件标识与其他业务去重键冲突"));
            assertSameDefinition(saved, normalized);
            return saved;
        }));
    }

    /**
     * 查询事件当前发布状态。
     *
     * @param key 事件主键
     * @return Outbox 记录
     */
    @Override
    public Optional<ReliableOutboxRecord> findEvent(ReliableEventKey key) {
        validateKey(key);
        return findEventInternal(key);
    }

    /**
     * 查询业务去重键绑定的事件。
     *
     * @param namespace 事件业务域
     * @param deduplicationKey 业务去重键
     * @return Outbox 记录
     */
    @Override
    public Optional<ReliableOutboxRecord> findByDeduplicationKey(
            String namespace,
            String deduplicationKey) {
        return findByDeduplicationKeyInternal(
                requireText(namespace, "namespace", 64),
                requireText(deduplicationKey, "deduplicationKey", 128));
    }

    /**
     * 按事件域和 Outbox 状态统计持久化事件，供业务侧暴露发布积压指标。
     *
     * @param namespace 事件业务域
     * @param status 待统计的 Outbox 状态
     * @return 当前状态记录数
     */
    @Override
    public long countEventsByStatus(String namespace, ReliableOutboxStatus status) {
        String normalizedNamespace = requireText(namespace, "namespace", 64);
        Objects.requireNonNull(status, "status");
        // 查询命中现有 namespace + status 索引，不扫描事件正文或业务载荷。
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reliable_outbox_event WHERE namespace = ? AND status = ?",
                Long.class, normalizedNamespace, status.name());
        return count == null ? 0L : count;
    }

    /**
     * 逐条围栏认领有限批次待发布事件，跨实例竞争时只有一个更新成功。
     *
     * @param namespace 事件业务域
     * @param owner 发布实例
     * @param now 当前时间
     * @param leaseUntil 租约截止时间
     * @param limit 最大数量
     * @return 已领取事件
     */
    @Override
    public List<ReliableOutboxRecord> claimPublishable(
            String namespace,
            String owner,
            Instant now,
            Instant leaseUntil,
            int limit) {
        String normalizedNamespace = requireText(namespace, "namespace", 64);
        String normalizedOwner = requireText(owner, "owner", 128);
        validateWindow(now, leaseUntil);
        validateLimit(limit);
        return requireResult(transactionTemplate.execute(status -> {
            // 候选查询不依赖长事务锁，每条更新再次匹配 PENDING 状态。
            List<ReliableEventKey> candidates = jdbcTemplate.query(
                    "SELECT namespace, event_id FROM t_reliable_outbox_event "
                            + "WHERE namespace = ? AND status = ? AND next_publish_at <= ? "
                            + "ORDER BY next_publish_at, updated_at LIMIT ?",
                    (rs, rowNum) -> new ReliableEventKey(rs.getString("namespace"), rs.getString("event_id")),
                    normalizedNamespace, ReliableOutboxStatus.PENDING.name(), timestamp(now), limit);
            List<ReliableOutboxRecord> claimed = new ArrayList<>();
            for (ReliableEventKey candidate : candidates) {
                int updated = jdbcTemplate.update(
                        "UPDATE t_reliable_outbox_event SET status = ?, publish_owner = ?, "
                                + "publish_lease_until = ?, publish_fencing_token = publish_fencing_token + 1, "
                                + "publish_attempt_count = publish_attempt_count + 1, updated_at = ? "
                                + "WHERE namespace = ? AND event_id = ? AND status = ? AND next_publish_at <= ?",
                        ReliableOutboxStatus.PUBLISHING.name(), normalizedOwner, timestamp(leaseUntil),
                        timestamp(now), candidate.namespace(), candidate.eventId(),
                        ReliableOutboxStatus.PENDING.name(), timestamp(now));
                if (updated == 1) {
                    claimed.add(findEventInternal(candidate).orElseThrow());
                }
            }
            return List.copyOf(claimed);
        }));
    }

    /**
     * 使用发布 owner 和 fencing token 提交消息代理确认。
     *
     * @param key 事件主键
     * @param lease 发布租约
     * @param eventVersion 事件版本
     * @param brokerMessageId 消息代理标识
     * @param now 确认时间
     * @return 是否成功提交
     */
    @Override
    public boolean markPublished(
            ReliableEventKey key,
            ReliableEventLease lease,
            long eventVersion,
            String brokerMessageId,
            Instant now) {
        validateKey(key);
        validateLease(lease);
        requirePositive(eventVersion, "eventVersion");
        String normalizedMessageId = requireText(brokerMessageId, "brokerMessageId", 128);
        Objects.requireNonNull(now, "now");
        return jdbcTemplate.update(
                "UPDATE t_reliable_outbox_event SET status = ?, broker_message_id = ?, published_at = ?, "
                        + "publish_owner = NULL, publish_lease_until = NULL, failure_category = NULL, "
                        + "failure_message = NULL, updated_at = ? WHERE namespace = ? AND event_id = ? "
                        + "AND event_version = ? AND status = ? AND publish_owner = ? AND publish_fencing_token = ?",
                ReliableOutboxStatus.PUBLISHED.name(), normalizedMessageId, timestamp(now), timestamp(now),
                key.namespace(), key.eventId(), eventVersion, ReliableOutboxStatus.PUBLISHING.name(),
                lease.owner(), lease.fencingToken()) == 1;
    }

    /**
     * 使用发布围栏记录失败并退回定时待发布状态。
     *
     * @param key 事件主键
     * @param lease 发布租约
     * @param eventVersion 事件版本
     * @param category 稳定失败分类
     * @param message 安全失败摘要
     * @param nextPublishAt 下一次发布时间
     * @param now 当前时间
     * @return 是否成功提交
     */
    @Override
    public boolean markPublishFailed(
            ReliableEventKey key,
            ReliableEventLease lease,
            long eventVersion,
            String category,
            String message,
            Instant nextPublishAt,
            Instant now) {
        validateKey(key);
        validateLease(lease);
        requirePositive(eventVersion, "eventVersion");
        Objects.requireNonNull(nextPublishAt, "nextPublishAt");
        Objects.requireNonNull(now, "now");
        return jdbcTemplate.update(
                "UPDATE t_reliable_outbox_event SET status = ?, next_publish_at = ?, "
                        + "publish_owner = NULL, publish_lease_until = NULL, failure_category = ?, "
                        + "failure_message = ?, updated_at = ? WHERE namespace = ? AND event_id = ? "
                        + "AND event_version = ? AND status = ? AND publish_owner = ? AND publish_fencing_token = ?",
                ReliableOutboxStatus.PENDING.name(), timestamp(nextPublishAt),
                requireText(category, "category", 64), sanitize(message), timestamp(now),
                key.namespace(), key.eventId(), eventVersion, ReliableOutboxStatus.PUBLISHING.name(),
                lease.owner(), lease.fencingToken()) == 1;
    }

    /**
     * 把发布租约过期记录恢复为立即可领取的 PENDING。
     *
     * @param namespace 事件业务域
     * @param now 当前时间
     * @param limit 最大数量
     * @return 恢复数量
     */
    @Override
    public int recoverExpiredPublications(String namespace, Instant now, int limit) {
        String normalizedNamespace = requireText(namespace, "namespace", 64);
        Objects.requireNonNull(now, "now");
        validateLimit(limit);
        return requireResult(transactionTemplate.execute(status -> {
            List<ReliableOutboxRecord> candidates = jdbcTemplate.query(
                    "SELECT " + OUTBOX_COLUMNS + " FROM t_reliable_outbox_event "
                            + "WHERE namespace = ? AND status = ? AND publish_lease_until <= ? "
                            + "ORDER BY publish_lease_until LIMIT ?",
                    this::mapOutbox, normalizedNamespace, ReliableOutboxStatus.PUBLISHING.name(),
                    timestamp(now), limit);
            int recovered = 0;
            for (ReliableOutboxRecord candidate : candidates) {
                int updated = jdbcTemplate.update(
                        "UPDATE t_reliable_outbox_event SET status = ?, next_publish_at = ?, "
                                + "publish_owner = NULL, publish_lease_until = NULL, failure_category = ?, "
                                + "failure_message = ?, updated_at = ? WHERE namespace = ? AND event_id = ? "
                                + "AND status = ? AND publish_owner = ? AND publish_fencing_token = ? "
                                + "AND publish_lease_until = ?",
                        ReliableOutboxStatus.PENDING.name(), timestamp(now), "PUBLISH_LEASE_EXPIRED",
                        "发布租约已过期", timestamp(now), candidate.key().namespace(), candidate.key().eventId(),
                        ReliableOutboxStatus.PUBLISHING.name(), candidate.lease().owner(),
                        candidate.lease().fencingToken(), timestamp(candidate.leaseUntil()));
                recovered += updated;
            }
            return recovered;
        }));
    }

    /**
     * 锁定 Outbox 后创建或重新领取指定消费者的 Inbox。
     *
     * @param eventKey 事件主键
     * @param eventVersion 事件版本
     * @param consumerName 稳定消费者名称
     * @param owner 消费实例
     * @param now 当前时间
     * @param leaseUntil 租约截止时间
     * @param maxAttempts 最大处理次数
     * @return 已领取 Inbox
     */
    @Override
    public Optional<ReliableInboxRecord> claimConsumption(
            ReliableEventKey eventKey,
            long eventVersion,
            String consumerName,
            String owner,
            Instant now,
            Instant leaseUntil,
            int maxAttempts) {
        validateKey(eventKey);
        requirePositive(eventVersion, "eventVersion");
        String normalizedConsumer = requireText(consumerName, "consumerName", 128);
        String normalizedOwner = requireText(owner, "owner", 128);
        validateWindow(now, leaseUntil);
        requirePositive(maxAttempts, "maxAttempts");
        return requireResult(transactionTemplate.execute(status -> {
            // Outbox 行锁串行化首次 Inbox 创建，避免重复 MQ 消息并发插入。
            Optional<ReliableOutboxRecord> event = findEventForUpdate(eventKey);
            if (event.isEmpty() || event.get().eventVersion() != eventVersion) {
                return Optional.empty();
            }
            ReliableInboxKey inboxKey = new ReliableInboxKey(eventKey, normalizedConsumer);
            Optional<ReliableInboxRecord> existing = findConsumptionInternal(inboxKey);
            if (existing.isEmpty()) {
                jdbcTemplate.update(
                        "INSERT INTO t_reliable_inbox_consumption "
                                + "(namespace, event_id, consumer_name, event_version, status, attempt_count, "
                                + "max_attempts, lease_owner, lease_until, fencing_token, started_at, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, 1, ?, ?, ?)",
                        eventKey.namespace(), eventKey.eventId(), normalizedConsumer, eventVersion,
                        ReliableInboxStatus.PROCESSING.name(), maxAttempts, normalizedOwner,
                        timestamp(leaseUntil), timestamp(now), timestamp(now), timestamp(now));
                return findConsumptionInternal(inboxKey);
            }
            ReliableInboxRecord current = existing.get();
            if (current.eventVersion() != eventVersion
                    || current.status() != ReliableInboxStatus.RETRY_WAIT
                    || current.nextRetryAt() == null
                    || now.isBefore(current.nextRetryAt())
                    || current.attemptCount() >= current.maxAttempts()) {
                return Optional.empty();
            }
            int updated = jdbcTemplate.update(
                    "UPDATE t_reliable_inbox_consumption SET status = ?, attempt_count = attempt_count + 1, "
                            + "lease_owner = ?, lease_until = ?, fencing_token = fencing_token + 1, "
                            + "next_retry_at = NULL, started_at = ?, failure_category = NULL, "
                            + "failure_message = NULL, updated_at = ? WHERE namespace = ? AND event_id = ? "
                            + "AND consumer_name = ? AND event_version = ? AND status = ? AND next_retry_at <= ?",
                    ReliableInboxStatus.PROCESSING.name(), normalizedOwner, timestamp(leaseUntil),
                    timestamp(now), timestamp(now), eventKey.namespace(), eventKey.eventId(),
                    normalizedConsumer, eventVersion, ReliableInboxStatus.RETRY_WAIT.name(), timestamp(now));
            return updated == 1 ? findConsumptionInternal(inboxKey) : Optional.empty();
        }));
    }

    /**
     * 查询指定消费者的 Inbox。
     *
     * @param key Inbox 主键
     * @return Inbox 记录
     */
    @Override
    public Optional<ReliableInboxRecord> findConsumption(ReliableInboxKey key) {
        validateInboxKey(key);
        return findConsumptionInternal(key);
    }

    /**
     * 按事件域、消费者和 Inbox 状态统计持久化消费记录，供业务侧暴露消费积压指标。
     *
     * @param namespace 事件业务域
     * @param consumerName 稳定消费者名称
     * @param status 待统计的 Inbox 状态
     * @return 当前状态记录数
     */
    @Override
    public long countConsumptionsByStatus(
            String namespace,
            String consumerName,
            ReliableInboxStatus status) {
        String normalizedNamespace = requireText(namespace, "namespace", 64);
        String normalizedConsumerName = requireText(consumerName, "consumerName", 128);
        Objects.requireNonNull(status, "status");
        // 查询命中消费者的状态索引，不读取处理异常或租约所有者等诊断明细。
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reliable_inbox_consumption "
                        + "WHERE namespace = ? AND consumer_name = ? AND status = ?",
                Long.class, normalizedNamespace, normalizedConsumerName, status.name());
        return count == null ? 0L : count;
    }

    /**
     * 使用消费围栏提交成功终态，重复或迟到提交返回 false。
     *
     * @param key Inbox 主键
     * @param lease 消费租约
     * @param now 完成时间
     * @return 是否成功提交
     */
    @Override
    public boolean completeConsumption(
            ReliableInboxKey key,
            ReliableEventLease lease,
            Instant now) {
        validateInboxKey(key);
        validateLease(lease);
        Objects.requireNonNull(now, "now");
        return jdbcTemplate.update(
                "UPDATE t_reliable_inbox_consumption SET status = ?, lease_owner = NULL, lease_until = NULL, "
                        + "next_retry_at = NULL, failure_category = NULL, failure_message = NULL, "
                        + "finished_at = ?, updated_at = ? WHERE namespace = ? AND event_id = ? "
                        + "AND consumer_name = ? AND status = ? AND lease_owner = ? AND fencing_token = ?",
                ReliableInboxStatus.SUCCEEDED.name(), timestamp(now), timestamp(now),
                key.eventKey().namespace(), key.eventKey().eventId(), key.consumerName(),
                ReliableInboxStatus.PROCESSING.name(), lease.owner(), lease.fencingToken()) == 1;
    }

    /**
     * 记录消费失败；未达到次数上限时在同一事务重新激活 Outbox。
     *
     * @param key Inbox 主键
     * @param lease 消费租约
     * @param category 稳定失败分类
     * @param message 安全失败摘要
     * @param nextRetryAt 下一次处理时间
     * @param now 当前时间
     * @return 更新后的消费状态
     */
    @Override
    public Optional<ReliableConsumptionResult> retryConsumption(
            ReliableInboxKey key,
            ReliableEventLease lease,
            String category,
            String message,
            Instant nextRetryAt,
            Instant now) {
        validateInboxKey(key);
        validateLease(lease);
        String normalizedCategory = requireText(category, "category", 64);
        Objects.requireNonNull(nextRetryAt, "nextRetryAt");
        Objects.requireNonNull(now, "now");
        return requireResult(transactionTemplate.execute(status -> {
            ReliableInboxRecord current = findConsumptionInternal(key)
                    .orElseThrow(() -> new IllegalStateException("Inbox 记录不存在"));
            boolean retry = current.attemptCount() < current.maxAttempts();
            ReliableInboxStatus target = retry
                    ? ReliableInboxStatus.RETRY_WAIT : ReliableInboxStatus.FAILED;
            int updated = jdbcTemplate.update(
                    "UPDATE t_reliable_inbox_consumption SET status = ?, lease_owner = NULL, lease_until = NULL, "
                            + "next_retry_at = ?, failure_category = ?, failure_message = ?, finished_at = ?, "
                            + "updated_at = ? WHERE namespace = ? AND event_id = ? AND consumer_name = ? "
                            + "AND status = ? AND lease_owner = ? AND fencing_token = ?",
                    target.name(), retry ? timestamp(nextRetryAt) : null, normalizedCategory, sanitize(message),
                    retry ? null : timestamp(now), timestamp(now), key.eventKey().namespace(),
                    key.eventKey().eventId(), key.consumerName(), ReliableInboxStatus.PROCESSING.name(),
                    lease.owner(), lease.fencingToken());
            if (updated != 1) {
                return Optional.empty();
            }
            if (retry) {
                // Inbox 重试和 Outbox 重新投递原子提交，避免只记录失败却永远没有下一条消息。
                jdbcTemplate.update(
                        "UPDATE t_reliable_outbox_event SET status = ?, next_publish_at = ?, "
                                + "publish_owner = NULL, publish_lease_until = NULL, broker_message_id = NULL, "
                                + "published_at = NULL, updated_at = ? WHERE namespace = ? AND event_id = ?",
                        ReliableOutboxStatus.PENDING.name(), timestamp(nextRetryAt), timestamp(now),
                        key.eventKey().namespace(), key.eventKey().eventId());
            }
            return Optional.of(new ReliableConsumptionResult(
                    findConsumptionInternal(key).orElseThrow(), retry));
        }));
    }

    /**
     * 重新激活达到重试上限的 Inbox，并将同一条已发布事件重新置为待发布。
     *
     * @param key Inbox 主键
     * @param category 稳定人工处置分类
     * @param message 安全原因摘要
     * @param nextRetryAt 下一次消费时间
     * @param now 状态迁移时间
     * @return 成功从 FAILED 迁移到 RETRY_WAIT 时返回 true
     */
    @Override
    public boolean resumeFailedConsumption(
            ReliableInboxKey key,
            String category,
            String message,
            Instant nextRetryAt,
            Instant now) {
        validateInboxKey(key);
        String normalizedCategory = requireText(category, "category", 64);
        Objects.requireNonNull(nextRetryAt, "nextRetryAt");
        Objects.requireNonNull(now, "now");
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // 保留事件版本但重置尝试计数，使人工触发的是同一只读任务的新一轮有限重试。
            int updated = jdbcTemplate.update(
                    "UPDATE t_reliable_inbox_consumption SET status = ?, attempt_count = 0, lease_owner = NULL, "
                            + "lease_until = NULL, fencing_token = fencing_token + 1, next_retry_at = ?, "
                            + "failure_category = ?, failure_message = ?, finished_at = NULL, updated_at = ? "
                            + "WHERE namespace = ? AND event_id = ? AND consumer_name = ? AND status = ?",
                    ReliableInboxStatus.RETRY_WAIT.name(), timestamp(nextRetryAt), normalizedCategory, sanitize(message),
                    timestamp(now), key.eventKey().namespace(), key.eventKey().eventId(), key.consumerName(),
                    ReliableInboxStatus.FAILED.name());
            if (updated != 1) {
                return false;
            }
            // Outbox 与 Inbox 在同一短事务内重新激活，避免只恢复状态却没有新的 Broker 投递。
            jdbcTemplate.update(
                    "UPDATE t_reliable_outbox_event SET status = ?, next_publish_at = ?, publish_owner = NULL, "
                            + "publish_lease_until = NULL, broker_message_id = NULL, published_at = NULL, "
                            + "failure_category = ?, failure_message = ?, updated_at = ? WHERE namespace = ? AND event_id = ?",
                    ReliableOutboxStatus.PENDING.name(), timestamp(nextRetryAt), normalizedCategory, sanitize(message),
                    timestamp(now), key.eventKey().namespace(), key.eventKey().eventId());
            return true;
        }));
    }

    /**
     * 查询消费进程崩溃后遗留的过期 PROCESSING 记录。
     *
     * @param namespace 事件业务域
     * @param consumerName 稳定消费者名称
     * @param now 当前时间
     * @param limit 最大数量
     * @return 到期 Inbox
     */
    @Override
    public List<ReliableInboxRecord> findExpiredConsumptions(
            String namespace,
            String consumerName,
            Instant now,
            int limit) {
        String normalizedNamespace = requireText(namespace, "namespace", 64);
        String normalizedConsumer = requireText(consumerName, "consumerName", 128);
        Objects.requireNonNull(now, "now");
        validateLimit(limit);
        return List.copyOf(jdbcTemplate.query(
                "SELECT " + INBOX_COLUMNS + " FROM t_reliable_inbox_consumption "
                        + "WHERE namespace = ? AND consumer_name = ? AND status = ? AND lease_until <= ? "
                        + "ORDER BY lease_until LIMIT ?",
                this::mapInbox, normalizedNamespace, normalizedConsumer,
                ReliableInboxStatus.PROCESSING.name(), timestamp(now), limit));
    }

    /**
     * 按主键读取 Outbox。
     *
     * @param key 事件主键
     * @return Outbox 记录
     */
    private Optional<ReliableOutboxRecord> findEventInternal(ReliableEventKey key) {
        return queryOptional(
                "SELECT " + OUTBOX_COLUMNS + " FROM t_reliable_outbox_event "
                        + "WHERE namespace = ? AND event_id = ?",
                this::mapOutbox, key.namespace(), key.eventId());
    }

    /**
     * 加锁读取 Outbox，用于串行化首次 Inbox 创建。
     *
     * @param key 事件主键
     * @return Outbox 记录
     */
    private Optional<ReliableOutboxRecord> findEventForUpdate(ReliableEventKey key) {
        return queryOptional(
                "SELECT " + OUTBOX_COLUMNS + " FROM t_reliable_outbox_event "
                        + "WHERE namespace = ? AND event_id = ? FOR UPDATE",
                this::mapOutbox, key.namespace(), key.eventId());
    }

    /**
     * 按业务去重键读取 Outbox。
     *
     * @param namespace 事件业务域
     * @param deduplicationKey 业务去重键
     * @return Outbox 记录
     */
    private Optional<ReliableOutboxRecord> findByDeduplicationKeyInternal(
            String namespace,
            String deduplicationKey) {
        return queryOptional(
                "SELECT " + OUTBOX_COLUMNS + " FROM t_reliable_outbox_event "
                        + "WHERE namespace = ? AND deduplication_key = ?",
                this::mapOutbox, namespace, deduplicationKey);
    }

    /**
     * 使用当前读查询业务去重键，确保并发 INSERT IGNORE 后能够看到获胜实例写入的记录。
     *
     * @param namespace 事件业务域
     * @param deduplicationKey 业务去重键
     * @return Outbox 记录
     */
    private Optional<ReliableOutboxRecord> findByDeduplicationKeyForUpdate(
            String namespace,
            String deduplicationKey) {
        return queryOptional(
                "SELECT " + OUTBOX_COLUMNS + " FROM t_reliable_outbox_event "
                        + "WHERE namespace = ? AND deduplication_key = ? FOR UPDATE",
                this::mapOutbox, namespace, deduplicationKey);
    }

    /**
     * 按主键读取 Inbox。
     *
     * @param key Inbox 主键
     * @return Inbox 记录
     */
    private Optional<ReliableInboxRecord> findConsumptionInternal(ReliableInboxKey key) {
        return queryOptional(
                "SELECT " + INBOX_COLUMNS + " FROM t_reliable_inbox_consumption "
                        + "WHERE namespace = ? AND event_id = ? AND consumer_name = ?",
                this::mapInbox, key.eventKey().namespace(), key.eventKey().eventId(), key.consumerName());
    }

    /**
     * 把 Outbox 查询行转换为不可变记录。
     *
     * @param rs 查询结果
     * @param rowNum 行号
     * @return Outbox 记录
     * @throws SQLException 读取失败
     */
    private ReliableOutboxRecord mapOutbox(ResultSet rs, int rowNum) throws SQLException {
        String owner = rs.getString("publish_owner");
        ReliableEventLease lease = owner == null ? null
                : new ReliableEventLease(owner, rs.getLong("publish_fencing_token"));
        return new ReliableOutboxRecord(
                new ReliableEventKey(rs.getString("namespace"), rs.getString("event_id")),
                rs.getString("deduplication_key"), rs.getString("event_type"),
                rs.getString("aggregate_id"), rs.getString("payload"), rs.getLong("event_version"),
                ReliableOutboxStatus.valueOf(rs.getString("status")), instant(rs, "next_publish_at"),
                lease, instant(rs, "publish_lease_until"), rs.getInt("publish_attempt_count"),
                rs.getString("broker_message_id"), instant(rs, "published_at"),
                rs.getString("failure_category"), rs.getString("failure_message"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    /**
     * 把 Inbox 查询行转换为不可变记录。
     *
     * @param rs 查询结果
     * @param rowNum 行号
     * @return Inbox 记录
     * @throws SQLException 读取失败
     */
    private ReliableInboxRecord mapInbox(ResultSet rs, int rowNum) throws SQLException {
        ReliableEventKey eventKey = new ReliableEventKey(
                rs.getString("namespace"), rs.getString("event_id"));
        String owner = rs.getString("lease_owner");
        ReliableEventLease lease = owner == null ? null
                : new ReliableEventLease(owner, rs.getLong("fencing_token"));
        return new ReliableInboxRecord(
                new ReliableInboxKey(eventKey, rs.getString("consumer_name")),
                rs.getLong("event_version"), ReliableInboxStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"), rs.getInt("max_attempts"), instant(rs, "next_retry_at"),
                lease, instant(rs, "lease_until"), rs.getString("failure_category"),
                rs.getString("failure_message"), instant(rs, "started_at"), instant(rs, "finished_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    /**
     * 执行最多返回一行的查询。
     *
     * @param sql SQL
     * @param mapper 行转换器
     * @param args 参数
     * @param <T> 结果类型
     * @return 可选结果
     */
    private <T> Optional<T> queryOptional(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = jdbcTemplate.query(sql, mapper, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 校验事件定义并返回原定义。
     *
     * @param definition 事件定义
     * @return 已校验定义
     */
    private ReliableEventDefinition validateDefinition(ReliableEventDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        validateKey(definition.key());
        requireText(definition.deduplicationKey(), "deduplicationKey", 128);
        requireText(definition.eventType(), "eventType", 64);
        requireText(definition.aggregateId(), "aggregateId", 128);
        Objects.requireNonNull(definition.payload(), "payload");
        requirePositive(definition.eventVersion(), "eventVersion");
        return definition;
    }

    /**
     * 拒绝同一业务去重键绑定不同不可变内容。
     *
     * @param existing 既有事件
     * @param definition 新定义
     */
    private void assertSameDefinition(
            ReliableOutboxRecord existing,
            ReliableEventDefinition definition) {
        if (!existing.eventType().equals(definition.eventType())
                || !existing.aggregateId().equals(definition.aggregateId())
                || !existing.payload().equals(definition.payload())
                || existing.eventVersion() != definition.eventVersion()) {
            throw new IllegalStateException("业务去重键已经绑定不同事件");
        }
    }

    /**
     * 校验事件主键。
     *
     * @param key 事件主键
     */
    private void validateKey(ReliableEventKey key) {
        Objects.requireNonNull(key, "key");
        requireText(key.namespace(), "namespace", 64);
        requireText(key.eventId(), "eventId", 128);
    }

    /**
     * 校验 Inbox 主键。
     *
     * @param key Inbox 主键
     */
    private void validateInboxKey(ReliableInboxKey key) {
        Objects.requireNonNull(key, "key");
        validateKey(key.eventKey());
        requireText(key.consumerName(), "consumerName", 128);
    }

    /**
     * 校验租约字段。
     *
     * @param lease 租约
     */
    private void validateLease(ReliableEventLease lease) {
        Objects.requireNonNull(lease, "lease");
        requireText(lease.owner(), "lease.owner", 128);
        requirePositive(lease.fencingToken(), "lease.fencingToken");
    }

    /**
     * 校验租约时间窗口。
     *
     * @param now 当前时间
     * @param leaseUntil 截止时间
     */
    private void validateWindow(Instant now, Instant leaseUntil) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("租约截止时间必须晚于当前时间");
        }
    }

    /**
     * 校验批次上限。
     *
     * @param limit 批次上限
     */
    private void validateLimit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1 到 1000 之间");
        }
    }

    /**
     * 校验正整数。
     *
     * @param value 数值
     * @param name 参数名
     */
    private void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " 必须大于零");
        }
    }

    /**
     * 校验并规范化限长文本。
     *
     * @param value 文本
     * @param name 参数名
     * @param maxLength 最大长度
     * @return 规范文本
     */
    private String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    /**
     * 生成安全的单行限长失败摘要。
     *
     * @param value 原始摘要
     * @return 安全摘要
     */
    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    /**
     * 把 Instant 转为 JDBC 时间戳。
     *
     * @param value 时间
     * @return JDBC 时间戳
     */
    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /**
     * 从结果集读取可空时间。
     *
     * @param rs 查询结果
     * @param column 列名
     * @return Instant 或 null
     * @throws SQLException 读取失败
     */
    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * 确保事务回调没有返回 null。
     *
     * @param value 回调结果
     * @param <T> 结果类型
     * @return 非空结果
     */
    private <T> T requireResult(T value) {
        return Objects.requireNonNull(value, "事务回调没有返回结果");
    }
}
