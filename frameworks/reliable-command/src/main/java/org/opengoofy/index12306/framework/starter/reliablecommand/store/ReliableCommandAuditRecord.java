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

package org.opengoofy.index12306.framework.starter.reliablecommand.store;

import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;

import java.time.Instant;

/**
 * 不包含原始请求和敏感结果正文的可靠命令状态迁移审计。
 *
 * @param id 审计流水主键
 * @param key 命令键
 * @param operatorId 执行实例或恢复器标识
 * @param oldStatus 原状态，首次认领时为空
 * @param newStatus 新状态
 * @param reason 稳定迁移原因
 * @param evidence 安全证据摘要
 * @param createdAt 创建时间
 */
public record ReliableCommandAuditRecord(
        long id,
        ReliableCommandKey key,
        String operatorId,
        ReliableCommandStatus oldStatus,
        ReliableCommandStatus newStatus,
        String reason,
        String evidence,
        Instant createdAt) {
}
