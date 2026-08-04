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
 * @param key 事件主键
 * @param deduplicationKey 业务去重键
 * @param eventType 事件类型
 * @param aggregateId 业务聚合标识
 * @param payload 不可变载荷
 * @param eventVersion 事件版本
 * @param status 发布状态
 * @param nextPublishAt 最早发布时间
 * @param lease 当前发布租约
 * @param leaseUntil 租约截止时间
 * @param publishAttemptCount 发布认领次数
 * @param brokerMessageId 消息代理返回的标识
 * @param publishedAt 消息代理确认时间
 * @param failureCategory 最近失败分类
 * @param failureMessage 最近失败摘要
 * @param createdAt 创建时间
 * @param updatedAt 修改时间
 */
public record ReliableOutboxRecord(
        ReliableEventKey key,
        String deduplicationKey,
        String eventType,
        String aggregateId,
        String payload,
        long eventVersion,
        ReliableOutboxStatus status,
        Instant nextPublishAt,
        ReliableEventLease lease,
        Instant leaseUntil,
        int publishAttemptCount,
        String brokerMessageId,
        Instant publishedAt,
        String failureCategory,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt) {
}
