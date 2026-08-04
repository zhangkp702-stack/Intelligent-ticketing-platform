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

/**
 * @param key Inbox 主键
 * @param eventVersion 已领取的事件版本
 * @param status 消费状态
 * @param attemptCount 已领取次数
 * @param maxAttempts 最大领取次数
 * @param nextRetryAt 下一次允许领取时间
 * @param lease 当前消费租约
 * @param leaseUntil 租约截止时间
 * @param failureCategory 最近失败分类
 * @param failureMessage 最近失败摘要
 * @param startedAt 最近开始时间
 * @param finishedAt 终态时间
 * @param createdAt 创建时间
 * @param updatedAt 修改时间
 */
public record ReliableInboxRecord(
        ReliableInboxKey key,
        long eventVersion,
        ReliableInboxStatus status,
        int attemptCount,
        int maxAttempts,
        Instant nextRetryAt,
        ReliableEventLease lease,
        Instant leaseUntil,
        String failureCategory,
        String failureMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt) {
}
