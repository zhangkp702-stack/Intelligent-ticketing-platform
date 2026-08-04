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
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableConsumptionResult;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventDefinition;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxStatus;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实数据库事务验证通用 Outbox、发布围栏和 Inbox 消费恢复。
 */
class JdbcReliableEventStoreTests {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
    private static final String NAMESPACE = "agent-action-reconciliation";
    private static final String CONSUMER = "agent-action-reconciliation-v1";

    private ReliableEventStore store;

    /**
     * 为每个测试创建独立 H2 数据库和可靠事件表。
     */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:event_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");

        // 独立数据库避免发布租约和消费次数在测试之间相互影响。
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        store = new JdbcReliableEventStore(jdbcTemplate, new DataSourceTransactionManager(dataSource));
    }

    /**
     * 验证业务去重键复用同一事件，并拒绝绑定不同不可变载荷。
     */
    @Test
    void shouldEnqueueOnceAndRejectConflictingDefinition() {
        ReliableEventDefinition definition = definition("event-1", "action-1", "payload-1");

        ReliableOutboxRecord first = store.enqueue(definition, NOW);
        ReliableOutboxRecord duplicate = store.enqueue(definition, NOW.plusSeconds(1));
        ReliableOutboxRecord sameBusinessKey = store.enqueue(
                definition("event-proposed-again", "action-1", "payload-1"), NOW.plusSeconds(2));

        assertThat(duplicate).isEqualTo(first);
        assertThat(sameBusinessKey).isEqualTo(first);
        assertThat(first.status()).isEqualTo(ReliableOutboxStatus.PENDING);
        assertThat(store.findByDeduplicationKey(NAMESPACE, "action-1")).contains(first);
        assertThatThrownBy(() -> store.enqueue(
                definition("event-1", "action-1", "different"), NOW.plusSeconds(2)))
                .hasMessageContaining("不同事件");
    }

    /**
     * 验证发布失败可延迟重试，旧围栏无法提交，新租约可以完成发布。
     */
    @Test
    void shouldFencePublisherAndRetryFailedPublication() {
        ReliableOutboxRecord event = store.enqueue(definition("event-2", "action-2", "payload"), NOW);
        ReliableOutboxRecord first = only(store.claimPublishable(
                NAMESPACE, "publisher-1", NOW, NOW.plusSeconds(30), 10));

        assertThat(store.markPublishFailed(
                event.key(), first.lease(), event.eventVersion(), "BROKER_TIMEOUT", "timeout",
                NOW.plusSeconds(10), NOW.plusSeconds(1))).isTrue();
        assertThat(store.claimPublishable(
                NAMESPACE, "publisher-2", NOW.plusSeconds(5), NOW.plusSeconds(35), 10)).isEmpty();

        ReliableOutboxRecord retried = only(store.claimPublishable(
                NAMESPACE, "publisher-2", NOW.plusSeconds(10), NOW.plusSeconds(40), 10));
        assertThat(store.markPublished(
                event.key(), first.lease(), event.eventVersion(), "old", NOW.plusSeconds(11))).isFalse();
        assertThat(store.markPublished(
                event.key(), retried.lease(), event.eventVersion(), "mq-2", NOW.plusSeconds(11))).isTrue();
        assertThat(store.findEvent(event.key()).orElseThrow().status())
                .isEqualTo(ReliableOutboxStatus.PUBLISHED);
    }

    /**
     * 验证发布进程在发送前宕机后，租约到期才能被恢复和重新认领。
     */
    @Test
    void shouldRecoverExpiredPublicationLease() {
        store.enqueue(definition("event-3", "action-3", "payload"), NOW);
        ReliableOutboxRecord first = only(store.claimPublishable(
                NAMESPACE, "publisher-1", NOW, NOW.plusSeconds(10), 10));

        assertThat(store.recoverExpiredPublications(NAMESPACE, NOW.plusSeconds(5), 10)).isZero();
        assertThat(store.recoverExpiredPublications(NAMESPACE, NOW.plusSeconds(10), 10)).isEqualTo(1);
        ReliableOutboxRecord recovered = only(store.claimPublishable(
                NAMESPACE, "publisher-2", NOW.plusSeconds(10), NOW.plusSeconds(20), 10));

        assertThat(recovered.lease().fencingToken()).isGreaterThan(first.lease().fencingToken());
        assertThat(store.markPublished(
                first.key(), first.lease(), first.eventVersion(), "late", NOW.plusSeconds(11))).isFalse();
    }

    /**
     * 验证重复消息只领取一次，失败会原子重新激活 Outbox，随后可成功消费。
     */
    @Test
    void shouldDeduplicateConsumptionAndRequeueOutbox() {
        ReliableOutboxRecord event = publish("event-4", "action-4");
        ReliableInboxRecord first = store.claimConsumption(
                event.key(), event.eventVersion(), CONSUMER, "consumer-1",
                NOW.plusSeconds(2), NOW.plusSeconds(32), 3).orElseThrow();

        assertThat(store.claimConsumption(
                event.key(), event.eventVersion(), CONSUMER, "consumer-2",
                NOW.plusSeconds(3), NOW.plusSeconds(33), 3)).isEmpty();
        ReliableConsumptionResult retry = store.retryConsumption(
                first.key(), first.lease(), "TEMPORARY", "retry",
                NOW.plusSeconds(12), NOW.plusSeconds(4)).orElseThrow();
        assertThat(retry.retryScheduled()).isTrue();
        assertThat(retry.record().status()).isEqualTo(ReliableInboxStatus.RETRY_WAIT);
        assertThat(store.findEvent(event.key()).orElseThrow().status())
                .isEqualTo(ReliableOutboxStatus.PENDING);

        ReliableInboxRecord second = store.claimConsumption(
                event.key(), event.eventVersion(), CONSUMER, "consumer-2",
                NOW.plusSeconds(12), NOW.plusSeconds(42), 3).orElseThrow();
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(store.completeConsumption(second.key(), second.lease(), NOW.plusSeconds(13))).isTrue();
        assertThat(store.completeConsumption(first.key(), first.lease(), NOW.plusSeconds(14))).isFalse();
        assertThat(store.findConsumption(second.key()).orElseThrow().status())
                .isEqualTo(ReliableInboxStatus.SUCCEEDED);
    }

    /**
     * 验证消费租约到期可被发现，达到次数上限后停止重新投递。
     */
    @Test
    void shouldExposeExpiredConsumptionAndStopAtAttemptLimit() {
        ReliableOutboxRecord event = publish("event-5", "action-5");
        ReliableInboxRecord first = store.claimConsumption(
                event.key(), event.eventVersion(), CONSUMER, "consumer-1",
                NOW.plusSeconds(2), NOW.plusSeconds(5), 1).orElseThrow();

        assertThat(store.findExpiredConsumptions(
                NAMESPACE, CONSUMER, NOW.plusSeconds(4), 10)).isEmpty();
        assertThat(store.findExpiredConsumptions(
                NAMESPACE, CONSUMER, NOW.plusSeconds(5), 10)).containsExactly(first);
        ReliableConsumptionResult exhausted = store.retryConsumption(
                first.key(), first.lease(), "CONSUMER_LEASE_EXPIRED", "expired",
                NOW.plusSeconds(5), NOW.plusSeconds(5)).orElseThrow();

        assertThat(exhausted.retryScheduled()).isFalse();
        assertThat(exhausted.record().status()).isEqualTo(ReliableInboxStatus.FAILED);
        assertThat(store.findEvent(event.key()).orElseThrow().status())
                .isEqualTo(ReliableOutboxStatus.PUBLISHED);
    }

    /**
     * 验证人工复核可以重新投递同一事件，但不会创建新事件或绕过 Inbox 去重边界。
     */
    @Test
    void shouldResumeFailedConsumptionAndRequeueSameOutboxEvent() {
        ReliableOutboxRecord event = publish("event-manual", "action-manual");
        ReliableInboxRecord first = store.claimConsumption(
                event.key(), event.eventVersion(), CONSUMER, "consumer-1",
                NOW.plusSeconds(2), NOW.plusSeconds(12), 1).orElseThrow();
        assertThat(store.retryConsumption(
                first.key(), first.lease(), "DOWNSTREAM_UNAVAILABLE", "unavailable",
                NOW.plusSeconds(3), NOW.plusSeconds(3)).orElseThrow().retryScheduled()).isFalse();

        // 人工恢复使用同一个 Inbox 与 Outbox 主键，重置有限尝试计数并安排新的 Broker 投递。
        assertThat(store.resumeFailedConsumption(
                first.key(), "MANUAL_RECONCILIATION_REQUEUED", "operator verified recovery",
                NOW.plusSeconds(4), NOW.plusSeconds(4))).isTrue();
        assertThat(store.findConsumption(first.key()).orElseThrow().status())
                .isEqualTo(ReliableInboxStatus.RETRY_WAIT);
        assertThat(store.findConsumption(first.key()).orElseThrow().attemptCount()).isZero();
        assertThat(store.findEvent(event.key()).orElseThrow().status()).isEqualTo(ReliableOutboxStatus.PENDING);
        assertThat(store.claimConsumption(
                event.key(), event.eventVersion(), CONSUMER, "consumer-2",
                NOW.plusSeconds(4), NOW.plusSeconds(14), 1)).isPresent();
    }

    /**
     * 验证按事件域、消费者和稳定状态读取 Outbox、Inbox 积压数量不会改变发布或消费租约。
     */
    @Test
    void shouldCountOutboxAndInboxByStatus() {
        ReliableOutboxRecord published = publish("event-count-published", "action-published");
        ReliableOutboxRecord pending = store.enqueue(definition("event-count-pending", "action-pending", "payload"), NOW);
        ReliableInboxRecord retryWaiting = store.claimConsumption(
                published.key(), published.eventVersion(), CONSUMER, "consumer-1",
                NOW.plusSeconds(2), NOW.plusSeconds(12), 3).orElseThrow();

        // 失败消费会让同一 Outbox 回到 PENDING，同时 Inbox 进入 RETRY_WAIT，二者必须分别可见。
        assertThat(store.retryConsumption(
                retryWaiting.key(), retryWaiting.lease(), "TEMPORARY", "retry",
                NOW.plusSeconds(10), NOW.plusSeconds(3))).isPresent();

        assertThat(store.countEventsByStatus(NAMESPACE, ReliableOutboxStatus.PENDING)).isEqualTo(2);
        assertThat(store.countEventsByStatus(NAMESPACE, ReliableOutboxStatus.PUBLISHED)).isZero();
        assertThat(store.countConsumptionsByStatus(NAMESPACE, CONSUMER, ReliableInboxStatus.RETRY_WAIT)).isEqualTo(1);
        assertThat(store.countConsumptionsByStatus(NAMESPACE, CONSUMER, ReliableInboxStatus.FAILED)).isZero();
        assertThat(store.findEvent(pending.key())).isPresent();
    }

    /**
     * 创建并发布测试事件。
     *
     * @param eventId 事件标识
     * @param actionId 业务动作标识
     * @return 已发布事件
     */
    private ReliableOutboxRecord publish(String eventId, String actionId) {
        ReliableOutboxRecord event = store.enqueue(definition(eventId, actionId, actionId), NOW);
        ReliableOutboxRecord claimed = only(store.claimPublishable(
                NAMESPACE, "publisher", NOW, NOW.plusSeconds(30), 10));
        assertThat(store.markPublished(
                event.key(), claimed.lease(), event.eventVersion(), "mq-1", NOW.plusSeconds(1))).isTrue();
        return store.findEvent(event.key()).orElseThrow();
    }

    /**
     * 创建测试事件定义。
     *
     * @param eventId 事件标识
     * @param actionId 业务动作标识
     * @param payload 载荷
     * @return 事件定义
     */
    private ReliableEventDefinition definition(String eventId, String actionId, String payload) {
        return new ReliableEventDefinition(
                new ReliableEventKey(NAMESPACE, eventId), actionId,
                "ACTION_RECONCILIATION_REQUESTED", actionId, payload, 1L);
    }

    /**
     * 断言列表只有一条记录。
     *
     * @param records 记录列表
     * @return 唯一记录
     */
    private ReliableOutboxRecord only(List<ReliableOutboxRecord> records) {
        assertThat(records).hasSize(1);
        return records.get(0);
    }
}
