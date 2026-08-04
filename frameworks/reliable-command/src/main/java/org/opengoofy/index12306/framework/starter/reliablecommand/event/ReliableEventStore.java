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

package org.opengoofy.index12306.framework.starter.reliablecommand.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 事务 Outbox、发布围栏和消费 Inbox 的统一持久化接口。
 */
public interface ReliableEventStore {

    /**
     * 幂等创建事件，相同业务去重键只能绑定完全相同的不可变定义。
     *
     * @param definition 事件定义
     * @param now 创建时间
     * @return 新建或既有事件
     */
    ReliableOutboxRecord enqueue(ReliableEventDefinition definition, Instant now);

    /**
     * 按事件主键查询 Outbox。
     *
     * @param key 事件主键
     * @return 事件记录
     */
    Optional<ReliableOutboxRecord> findEvent(ReliableEventKey key);

    /**
     * 按业务去重键查询 Outbox。
     *
     * @param namespace 事件业务域
     * @param deduplicationKey 业务去重键
     * @return 事件记录
     */
    Optional<ReliableOutboxRecord> findByDeduplicationKey(String namespace, String deduplicationKey);

    /**
     * 统计指定事件域中某个 Outbox 状态的记录数，用于健康监控和告警，不参与发布认领。
     *
     * @param namespace 事件业务域
     * @param status 待统计的 Outbox 状态
     * @return 当前状态记录数
     */
    long countEventsByStatus(String namespace, ReliableOutboxStatus status);

    /**
     * 用短事务和围栏令牌认领有限批次的待发布事件。
     *
     * @param namespace 事件业务域
     * @param owner 发布实例
     * @param now 当前时间
     * @param leaseUntil 租约截止时间
     * @param limit 最大数量
     * @return 已取得发布租约的事件
     */
    List<ReliableOutboxRecord> claimPublishable(
            String namespace, String owner, Instant now, Instant leaseUntil, int limit);

    /**
     * 使用发布围栏记录消息代理确认结果。
     *
     * @param key 事件主键
     * @param lease 发布租约
     * @param eventVersion 事件版本
     * @param brokerMessageId 消息代理标识
     * @param now 确认时间
     * @return 是否成功提交
     */
    boolean markPublished(
            ReliableEventKey key,
            ReliableEventLease lease,
            long eventVersion,
            String brokerMessageId,
            Instant now);

    /**
     * 记录发布失败并安排下一次发布。
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
    boolean markPublishFailed(
            ReliableEventKey key,
            ReliableEventLease lease,
            long eventVersion,
            String category,
            String message,
            Instant nextPublishAt,
            Instant now);

    /**
     * 回收发布进程崩溃后遗留的过期租约。
     *
     * @param namespace 事件业务域
     * @param now 当前时间
     * @param limit 最大数量
     * @return 恢复数量
     */
    int recoverExpiredPublications(String namespace, Instant now, int limit);

    /**
     * 幂等领取指定消费者的事件处理权。
     *
     * @param eventKey 事件主键
     * @param eventVersion 消息携带的事件版本
     * @param consumerName 稳定消费者名称
     * @param owner 消费实例
     * @param now 当前时间
     * @param leaseUntil 租约截止时间
     * @param maxAttempts 最大处理次数
     * @return 成功领取后的 Inbox
     */
    Optional<ReliableInboxRecord> claimConsumption(
            ReliableEventKey eventKey,
            long eventVersion,
            String consumerName,
            String owner,
            Instant now,
            Instant leaseUntil,
            int maxAttempts);

    /**
     * 查询消费者当前 Inbox 状态。
     *
     * @param key Inbox 主键
     * @return Inbox 记录
     */
    Optional<ReliableInboxRecord> findConsumption(ReliableInboxKey key);

    /**
     * 统计指定消费者在某个 Inbox 状态的记录数，用于健康监控和告警，不参与消费认领。
     *
     * @param namespace 事件业务域
     * @param consumerName 稳定消费者名称
     * @param status 待统计的 Inbox 状态
     * @return 当前状态记录数
     */
    long countConsumptionsByStatus(
            String namespace,
            String consumerName,
            ReliableInboxStatus status);

    /**
     * 使用消费围栏提交成功终态。
     *
     * @param key Inbox 主键
     * @param lease 消费租约
     * @param now 完成时间
     * @return 是否成功提交
     */
    boolean completeConsumption(ReliableInboxKey key, ReliableEventLease lease, Instant now);

    /**
     * 使用消费围栏记录失败，未达到上限时同步重新激活 Outbox。
     *
     * @param key Inbox 主键
     * @param lease 消费租约
     * @param category 稳定失败分类
     * @param message 安全失败摘要
     * @param nextRetryAt 下一次处理时间
     * @param now 当前时间
     * @return 更新后的消费状态，围栏竞争失败时为空
     */
    Optional<ReliableConsumptionResult> retryConsumption(
            ReliableInboxKey key,
            ReliableEventLease lease,
            String category,
            String message,
            Instant nextRetryAt,
            Instant now);

    /**
     * 由人工处置重新激活已经耗尽重试次数的 Inbox，并同步重新投递同一条 Outbox 事件。
     *
     * <p>该方法只允许 FAILED Inbox 回到 RETRY_WAIT；不会改变事件版本或创建新的业务事件。</p>
     *
     * @param key Inbox 主键
     * @param category 稳定人工处置分类
     * @param message 安全原因摘要
     * @param nextRetryAt 下一次消费时间
     * @param now 状态迁移时间
     * @return 成功重新激活时返回 true
     */
    boolean resumeFailedConsumption(
            ReliableInboxKey key,
            String category,
            String message,
            Instant nextRetryAt,
            Instant now);

    /**
     * 查询已经超过消费租约的运行记录，具体恢复由业务协调器决定。
     *
     * @param namespace 事件业务域
     * @param consumerName 稳定消费者名称
     * @param now 当前时间
     * @param limit 最大数量
     * @return 到期 Inbox
     */
    List<ReliableInboxRecord> findExpiredConsumptions(
            String namespace, String consumerName, Instant now, int limit);
}
