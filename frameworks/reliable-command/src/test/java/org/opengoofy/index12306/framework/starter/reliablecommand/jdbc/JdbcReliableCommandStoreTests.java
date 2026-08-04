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

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandClaim;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandDefinition;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandMode;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandService;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;
import org.opengoofy.index12306.framework.starter.reliablecommand.store.ReliableCommandAuditRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.store.ReliableCommandStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实关系数据库事务验证可靠命令竞争、围栏和恢复行为。
 */
class JdbcReliableCommandStoreTests {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private ReliableCommandStore store;

    /**
     * 为每个测试创建独立 H2 数据库并初始化通用表结构。
     */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:reliable_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");

        // 独立数据库保证并发和回滚测试之间不会共享命令记录。
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        store = new JdbcReliableCommandStore(jdbcTemplate, transactionManager);
    }

    /**
     * 验证重复命令可以区分处理中、成功重放、类型冲突、正文冲突和归属冲突。
     */
    @Test
    void shouldClassifyDuplicateClaimsAndReplaySuccess() {
        ReliableCommandDefinition definition = definition("cmd-1", "user-1", "fingerprint-1");
        ReliableCommandClaim first = store.claim(definition, "worker-1", NOW, NOW.plusSeconds(30));
        ReliableCommandClaim processing = store.claim(definition, "worker-2", NOW, NOW.plusSeconds(30));

        assertThat(first.outcome()).isEqualTo(ReliableCommandClaim.Outcome.ACQUIRED);
        assertThat(processing.outcome()).isEqualTo(ReliableCommandClaim.Outcome.PROCESSING);

        // 只有首次认领得到的围栏租约可以落成功结果，后续同请求直接读取该结果。
        assertThat(store.markSucceeded(
                definition.key(), first.record().lease(), "{\"orderSn\":\"O1\"}", "O1", NOW.plusSeconds(2)))
                .isTrue();
        ReliableCommandClaim replay = store.claim(definition, "worker-3", NOW, NOW.plusSeconds(30));
        assertThat(replay.outcome()).isEqualTo(ReliableCommandClaim.Outcome.REPLAY_SUCCEEDED);
        assertThat(replay.record().resultPayload()).isEqualTo("{\"orderSn\":\"O1\"}");

        ReliableCommandDefinition payloadConflict = definition("cmd-1", "user-1", "fingerprint-2");
        ReliableCommandDefinition typeConflict = new ReliableCommandDefinition(
                definition.key(), "CANCEL_TICKET_ORDER", ReliableCommandMode.REMOTE_EFFECT,
                "user-1", "fingerprint-1", "v1", null);
        ReliableCommandDefinition ownerConflict = new ReliableCommandDefinition(
                new ReliableCommandKey("ticket.purchase", "cmd-1", "user-1"),
                "PURCHASE_TICKET",
                ReliableCommandMode.REMOTE_EFFECT,
                "user-2", "fingerprint-1", "v1", null);
        assertThat(store.claim(payloadConflict, "worker-4", NOW, NOW.plusSeconds(30)).outcome())
                .isEqualTo(ReliableCommandClaim.Outcome.PAYLOAD_MISMATCH);
        assertThat(store.claim(typeConflict, "worker-4", NOW, NOW.plusSeconds(30)).outcome())
                .isEqualTo(ReliableCommandClaim.Outcome.PAYLOAD_MISMATCH);
        assertThat(store.claim(ownerConflict, "worker-4", NOW, NOW.plusSeconds(30)).outcome())
                .isEqualTo(ReliableCommandClaim.Outcome.OWNER_MISMATCH);
        assertThat(store.findAudit(definition.key())).hasSize(2);
    }

    /**
     * 验证 UNKNOWN 只能通过只读对账恢复，旧围栏令牌不能覆盖新结果。
     */
    @Test
    void shouldFenceStaleExecutionAndReconcileUnknownResult() {
        ReliableCommandDefinition definition = new ReliableCommandDefinition(
                new ReliableCommandKey("ticket.purchase", "cmd-2", "user-1"),
                "PURCHASE_TICKET",
                ReliableCommandMode.REMOTE_EFFECT,
                "user-1", "fingerprint-1", "v1", "PENDING-O2");
        ReliableCommandClaim first = store.claim(definition, "worker-1", NOW, NOW.plusSeconds(30));
        ReliableCommandLease staleLease = first.record().lease();

        // 远程调用超时后释放真实执行权并安排权威查询，不允许自动重放真实写操作。
        assertThat(store.markUnknown(
                definition.key(), staleLease, "REMOTE_TIMEOUT", "x".repeat(1000),
                NOW.plusSeconds(5), NOW.plusSeconds(1))).isTrue();
        assertThat(store.find(definition.key()).orElseThrow().businessReference()).isEqualTo("PENDING-O2");
        assertThat(store.find(definition.key()).orElseThrow().failureMessage()).hasSize(512);
        ReliableCommandRecord reconciliation = store.claimReconciliation(
                definition.key(), "reconciler-1", NOW.plusSeconds(5), NOW.plusSeconds(35)).orElseThrow();
        assertThat(reconciliation.fencingToken()).isGreaterThan(staleLease.fencingToken());
        assertThat(store.markSucceeded(
                definition.key(), staleLease, "stale", null, NOW.plusSeconds(6))).isFalse();

        // 新对账租约确认权威成功后，重复请求才可以复用恢复结果。
        assertThat(store.reconcileSucceeded(
                definition.key(), reconciliation.lease(), "{\"orderSn\":\"O2\"}", "O2",
                "order-service=SUCCEEDED", NOW.plusSeconds(6))).isTrue();
        assertThat(store.find(definition.key()).orElseThrow().status())
                .isEqualTo(ReliableCommandStatus.SUCCEEDED);
    }

    /**
     * 验证只有权威只读查询可以把未知命令收敛为明确失败。
     */
    @Test
    void shouldReconcileUnknownResultAsDefiniteFailure() {
        ReliableCommandDefinition definition = definition("cmd-failed", "user-1", "fingerprint-1");
        ReliableCommandClaim first = store.claim(definition, "worker-1", NOW, NOW.plusSeconds(30));
        assertThat(store.markUnknown(
                definition.key(), first.record().lease(), "REMOTE_TIMEOUT", "timeout",
                NOW.plusSeconds(2), NOW.plusSeconds(1))).isTrue();
        ReliableCommandRecord reconciliation = store.claimReconciliation(
                definition.key(), "reconciler-1", NOW.plusSeconds(2), NOW.plusSeconds(30)).orElseThrow();

        // 明确失败必须由新对账围栏提交，并保留有限失败摘要和权威证据。
        assertThat(store.reconcileFailed(
                definition.key(), reconciliation.lease(), "DOWNSTREAM_FAILED", "rejected",
                "ticket-operation=FAILED", NOW.plusSeconds(3))).isTrue();
        assertThat(store.find(definition.key()).orElseThrow().status())
                .isEqualTo(ReliableCommandStatus.FAILED);
        assertThat(store.findAudit(definition.key())).last()
                .extracting(ReliableCommandAuditRecord::reason)
                .isEqualTo("RECONCILIATION_CONFIRMED_FAILURE");
    }

    /**
     * 验证真实执行和对账租约过期后都会安全回到 UNKNOWN。
     */
    @Test
    void shouldRecoverExpiredLeasesWithoutReplayingEffects() {
        ReliableCommandDefinition definition = definition("cmd-3", "user-1", "fingerprint-1");
        store.claim(definition, "worker-1", NOW, NOW.plusSeconds(10));

        assertThat(store.recoverExpiredLeases(
                "ticket.purchase", NOW.plusSeconds(11), "recovery-1", 100)).isEqualTo(1);
        assertThat(store.find(definition.key()).orElseThrow().status())
                .isEqualTo(ReliableCommandStatus.UNKNOWN);

        ReliableCommandRecord reconciliation = store.claimReconciliation(
                definition.key(), "reconciler-1", NOW.plusSeconds(11), NOW.plusSeconds(20)).orElseThrow();
        assertThat(reconciliation.status()).isEqualTo(ReliableCommandStatus.RECONCILING);
        assertThat(store.recoverExpiredLeases(
                "ticket.purchase", NOW.plusSeconds(21), "recovery-1", 100)).isEqualTo(1);
        assertThat(store.find(definition.key()).orElseThrow().status())
                .isEqualTo(ReliableCommandStatus.UNKNOWN);

        // 自动查询耗尽后可以通过新对账租约转入人工处理，不能继续安排无界重试。
        ReliableCommandRecord finalReconciliation = store.claimReconciliation(
                definition.key(), "reconciler-2", NOW.plusSeconds(21), NOW.plusSeconds(30)).orElseThrow();
        assertThat(store.finishReconciliation(
                definition.key(), finalReconciliation.lease(), ReliableCommandStatus.MANUAL_REVIEW,
                "RECONCILIATION_EXHAUSTED", "自动对账次数耗尽", "downstream=NOT_FOUND",
                null, NOW.plusSeconds(22))).isTrue();
        assertThat(store.find(definition.key()).orElseThrow().status())
                .isEqualTo(ReliableCommandStatus.MANUAL_REVIEW);
    }

    /**
     * 验证人工只能把耗尽的对账任务重新安排为只读查询，并留下可追溯的操作员审计。
     */
    @Test
    void shouldResumeManualReviewAsReadOnlyReconciliation() {
        ReliableCommandDefinition definition = definition("cmd-manual", "user-1", "fingerprint-1");
        ReliableCommandClaim first = store.claim(definition, "worker-1", NOW, NOW.plusSeconds(10));
        assertThat(store.markUnknown(
                definition.key(), first.record().lease(), "REMOTE_TIMEOUT", "timeout",
                NOW.plusSeconds(1), NOW.plusSeconds(1))).isTrue();
        ReliableCommandRecord reconciliation = store.claimReconciliation(
                definition.key(), "reconciler-1", NOW.plusSeconds(1), NOW.plusSeconds(10)).orElseThrow();
        assertThat(store.finishReconciliation(
                definition.key(), reconciliation.lease(), ReliableCommandStatus.MANUAL_REVIEW,
                "RECONCILIATION_EXHAUSTED", "exhausted", "downstream=UNAVAILABLE",
                null, NOW.plusSeconds(2))).isTrue();

        // 人工重启保留原始命令，只恢复 UNKNOWN 调度；下一步仍必须通过 claimReconciliation 获取只读租约。
        assertThat(store.resumeManualReview(
                definition.key(), "operator-1", "downstream recovered", NOW.plusSeconds(3), NOW.plusSeconds(3)))
                .isTrue();
        assertThat(store.find(definition.key()).orElseThrow().status()).isEqualTo(ReliableCommandStatus.UNKNOWN);
        assertThat(store.findAudit(definition.key())).last()
                .extracting(ReliableCommandAuditRecord::reason)
                .isEqualTo("MANUAL_RECONCILIATION_REQUEUED");
        assertThat(store.claimReconciliation(
                definition.key(), "reconciler-2", NOW.plusSeconds(3), NOW.plusSeconds(13))).isPresent();
    }

    /**
     * 验证按命令域和稳定状态读取积压数量不会读取业务载荷或修改命令状态。
     */
    @Test
    void shouldCountCommandsByNamespaceAndStatus() {
        ReliableCommandDefinition processing = definition("cmd-count-processing", "user-1", "fingerprint-1");
        ReliableCommandDefinition unknown = definition("cmd-count-unknown", "user-2", "fingerprint-2");
        ReliableCommandClaim unknownClaim = store.claim(unknown, "worker-1", NOW, NOW.plusSeconds(30));

        // 两条命令位于同一命名空间，其中一条被安全转入 UNKNOWN 供积压监控查询。
        store.claim(processing, "worker-1", NOW, NOW.plusSeconds(30));
        assertThat(store.markUnknown(
                unknown.key(), unknownClaim.record().lease(), "REMOTE_TIMEOUT", "timeout",
                NOW.plusSeconds(10), NOW.plusSeconds(1))).isTrue();

        assertThat(store.countByStatus("ticket.purchase", ReliableCommandStatus.PROCESSING)).isEqualTo(1);
        assertThat(store.countByStatus("ticket.purchase", ReliableCommandStatus.UNKNOWN)).isEqualTo(1);
        assertThat(store.countByStatus("ticket.purchase", ReliableCommandStatus.MANUAL_REVIEW)).isZero();
    }

    /**
     * 验证 LOCAL_ATOMIC 命令记录可以和业务写入加入同一本地事务。
     */
    @Test
    void shouldRollbackCommandAndBusinessEffectTogether() {
        ReliableCommandDefinition definition = new ReliableCommandDefinition(
                new ReliableCommandKey("order.create", "cmd-local", "user-1"),
                "CREATE_ORDER",
                ReliableCommandMode.LOCAL_ATOMIC,
                "user-1", "fingerprint-1", "v1", null);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // 模拟业务写入后抛出异常，命令认领和业务数据必须同时回滚。
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            ReliableCommandClaim claim = store.claim(definition, "worker-1", NOW, NOW.plusSeconds(30));
            assertThat(claim.acquired()).isTrue();
            jdbcTemplate.update(
                    "INSERT INTO t_business_effect (command_id, effect_value) VALUES (?, ?)",
                    definition.key().commandId(), "created");
            throw new IllegalStateException("rollback business transaction");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.find(definition.key())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_business_effect", Integer.class)).isZero();
    }

    /**
     * 验证远程副作用命令认领使用独立短事务，不会随调用方异常一起回滚。
     */
    @Test
    void shouldCommitRemoteClaimOutsideCallerRollback() {
        ReliableCommandDefinition definition = definition("cmd-remote", "user-1", "fingerprint-1");
        ReliableCommandService service = new ReliableCommandService(
                store, transactionManager, Duration.ofSeconds(30), "worker-remote");
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);
        AtomicReference<ReliableCommandRecord> claimed = new AtomicReference<>();

        // 模拟调用方随后回滚，REQUIRES_NEW 认领记录仍必须保留以阻止盲目重放远程写操作。
        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
            claimed.set(service.claim(definition).record());
            throw new IllegalStateException("rollback caller transaction");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.find(definition.key())).isPresent();
        service.release(claimed.get());
    }

    /**
     * 验证多线程同时认领同一命令时只有一个实例获得执行权。
     *
     * @throws Exception 并发任务执行失败
     */
    @Test
    void shouldAllowOnlyOneConcurrentClaim() throws Exception {
        ReliableCommandDefinition definition = definition("cmd-concurrent", "user-1", "fingerprint-1");
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReliableCommandClaim>> futures = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return store.claim(definition, "worker-" + index, NOW, NOW.plusSeconds(30));
                    }))
                    .toList();
            start.countDown();

            // 数据库复合主键是竞争裁决点，其他线程只能看到同一条 PROCESSING 记录。
            List<ReliableCommandClaim.Outcome> outcomes = futures.stream()
                    .map(this::getFuture)
                    .map(ReliableCommandClaim::outcome)
                    .toList();
            assertThat(outcomes).containsExactlyInAnyOrder(
                    ReliableCommandClaim.Outcome.ACQUIRED,
                    ReliableCommandClaim.Outcome.PROCESSING,
                    ReliableCommandClaim.Outcome.PROCESSING,
                    ReliableCommandClaim.Outcome.PROCESSING,
                    ReliableCommandClaim.Outcome.PROCESSING,
                    ReliableCommandClaim.Outcome.PROCESSING);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 读取并发任务结果并将受检异常转换为测试失败。
     *
     * @param future 并发认领任务
     * @return 认领结果
     */
    private ReliableCommandClaim getFuture(Future<ReliableCommandClaim> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError("Concurrent claim failed", exception);
        }
    }

    /**
     * 创建远程副作用测试命令定义。
     *
     * @param commandId 命令标识
     * @param ownerId 所属用户
     * @param fingerprint 请求摘要
     * @return 测试命令定义
     */
    private ReliableCommandDefinition definition(String commandId, String ownerId, String fingerprint) {
        return new ReliableCommandDefinition(
                new ReliableCommandKey("ticket.purchase", commandId, ownerId),
                "PURCHASE_TICKET",
                ReliableCommandMode.REMOTE_EFFECT,
                ownerId,
                fingerprint,
                "v1",
                null);
    }
}
