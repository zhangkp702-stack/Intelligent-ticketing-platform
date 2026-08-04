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

package org.opengoofy.index12306.framework.starter.reliablecommand.core;

import java.time.Instant;

/**
 * 可靠命令表的领域只读视图。
 *
 * @param key 稳定命令键
 * @param commandType 稳定业务命令类型
 * @param mode 副作用执行模式
 * @param ownerId 命令所属主体
 * @param requestFingerprint 请求摘要
 * @param fingerprintVersion 摘要规则版本
 * @param status 当前状态
 * @param resultPayload 成功结果序列化文本
 * @param failureCategory 失败或未知分类
 * @param failureMessage 限长故障摘要
 * @param businessReference 安全业务引用
 * @param leaseOwner 当前租约实例
 * @param leaseUntil 当前租约截止时间
 * @param fencingToken 围栏令牌
 * @param lastHeartbeatAt 最近心跳时间
 * @param attemptCount 执行认领次数
 * @param nextReconcileAt 下一次对账时间
 * @param reconcileAttemptCount 对账认领次数
 * @param createdAt 创建时间
 * @param updatedAt 修改时间
 */
public record ReliableCommandRecord(
        ReliableCommandKey key,
        String commandType,
        ReliableCommandMode mode,
        String ownerId,
        String requestFingerprint,
        String fingerprintVersion,
        ReliableCommandStatus status,
        String resultPayload,
        String failureCategory,
        String failureMessage,
        String businessReference,
        String leaseOwner,
        Instant leaseUntil,
        long fencingToken,
        Instant lastHeartbeatAt,
        int attemptCount,
        Instant nextReconcileAt,
        int reconcileAttemptCount,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 返回当前记录携带的有效租约。
     *
     * @return 当前租约；记录未持有租约时返回 null
     */
    public ReliableCommandLease lease() {
        // 只有 owner 和截止时间同时存在时才构成可用于围栏更新的完整租约。
        if (leaseOwner == null || leaseUntil == null) {
            return null;
        }
        return new ReliableCommandLease(leaseOwner, leaseUntil, fencingToken);
    }
}
