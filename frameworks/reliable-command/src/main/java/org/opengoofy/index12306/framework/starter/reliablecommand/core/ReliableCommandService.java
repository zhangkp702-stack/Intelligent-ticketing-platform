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

import org.opengoofy.index12306.framework.starter.reliablecommand.store.ReliableCommandStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 为远程副作用命令提供短事务认领、活动租约心跳和对账状态迁移。
 *
 * <p>真实业务调用不在数据库事务中执行。每次状态读写都使用独立短事务，避免远程调用长期占用连接，
 * 同时确保异常路径能够在调用方事务回滚后持久化 UNKNOWN。</p>
 */
public class ReliableCommandService {

    private final ReliableCommandStore store;
    private final TransactionTemplate isolatedTransaction;
    private final Duration executionLeaseDuration;
    private final String workerId;
    private final ConcurrentMap<ReliableCommandKey, ReliableCommandLease> activeLeases = new ConcurrentHashMap<>();

    /**
     * 创建远程可靠命令服务。
     *
     * @param store 可靠命令持久化接口
     * @param transactionManager 当前业务数据源事务管理器
     * @param executionLeaseDuration 真实业务执行租约时长
     * @param workerId 当前服务实例标识
     */
    public ReliableCommandService(
            ReliableCommandStore store,
            PlatformTransactionManager transactionManager,
            Duration executionLeaseDuration,
            String workerId) {
        this.store = Objects.requireNonNull(store, "store");
        this.isolatedTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.isolatedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.executionLeaseDuration = requirePositive(executionLeaseDuration, "executionLeaseDuration");
        this.workerId = requireText(workerId, "workerId");
    }

    /**
     * 在独立短事务中认领命令，并为首次获得执行权的记录登记 JVM 活动心跳。
     *
     * @param definition 已完成业务字段校验的命令定义
     * @return 首次认领或重复请求判定结果
     */
    public ReliableCommandClaim claim(ReliableCommandDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        Instant now = Instant.now();
        // 数据库唯一键决定唯一执行者，JVM 活动表只负责为当前仍在运行的调用续租。
        ReliableCommandClaim claim = requiresNew(() -> store.claim(
                definition, workerId, now, now.plus(executionLeaseDuration)));
        if (claim.acquired()) {
            ReliableCommandLease lease = Objects.requireNonNull(claim.record().lease(), "claimed lease");
            activeLeases.put(claim.record().key(), lease);
        }
        return claim;
    }

    /**
     * 在独立短事务中查询命令状态。
     *
     * @param key 稳定命令键
     * @return 当前权威命令记录
     */
    public Optional<ReliableCommandRecord> find(ReliableCommandKey key) {
        Objects.requireNonNull(key, "key");
        // 查询不参与调用方可能存在的长事务，避免读取旧快照。
        return requiresNew(() -> store.find(key));
    }

    /**
     * 使用认领时获得的围栏租约保存成功结果。
     *
     * @param record 首次认领得到的命令记录
     * @param resultPayload 可供重复请求重放的结果
     * @param businessReference 订单号等安全业务引用
     * @return 当前租约仍有效且保存成功时返回 true
     */
    public boolean markSucceeded(
            ReliableCommandRecord record,
            String resultPayload,
            String businessReference) {
        ReliableCommandLease lease = requireLease(record);
        Instant now = Instant.now();
        // 成功结果必须经过 owner 和 fencing token 双重校验，拒绝旧实例迟到回写。
        return requiresNew(() -> store.markSucceeded(
                record.key(), lease, resultPayload, businessReference, now));
    }

    /**
     * 将无法证明远程副作用未成功的执行持久化为 UNKNOWN。
     *
     * @param record 首次认领得到的命令记录
     * @param failureCategory 稳定未知分类
     * @param failureMessage 限长异常摘要
     * @param nextReconcileAt 下一次只读对账时间
     * @return 当前租约仍有效且状态迁移成功时返回 true
     */
    public boolean markUnknown(
            ReliableCommandRecord record,
            String failureCategory,
            String failureMessage,
            Instant nextReconcileAt) {
        ReliableCommandLease lease = requireLease(record);
        Instant now = Instant.now();
        // UNKNOWN 必须独立提交，不能随调用方业务异常一起回滚。
        return requiresNew(() -> store.markUnknown(
                record.key(), lease, failureCategory, failureMessage, nextReconcileAt, now));
    }

    /**
     * 结束当前 JVM 对命令的心跳维护。
     *
     * @param record 首次认领得到的命令记录
     */
    public void release(ReliableCommandRecord record) {
        ReliableCommandLease lease = requireLease(record);
        // compare-and-remove 避免旧请求结束时删除后来重新登记的租约。
        activeLeases.remove(record.key(), lease);
    }

    /**
     * 回收指定命令域内已经过期的执行或对账租约。
     *
     * @param namespace 命令域
     * @param limit 单批上限
     * @return 成功迁移到 UNKNOWN 的记录数量
     */
    public int recoverExpiredLeases(String namespace, int limit) {
        Instant now = Instant.now();
        // 恢复只迁移持久状态，绝不重新调用真实业务写接口。
        return requiresNew(() -> store.recoverExpiredLeases(namespace, now, workerId, limit));
    }

    /**
     * 查询指定命令域内已经到期的 UNKNOWN 记录。
     *
     * @param namespace 命令域
     * @param limit 单批上限
     * @return 待执行权威只读查询的记录
     */
    public List<ReliableCommandRecord> findDueReconciliations(String namespace, int limit) {
        Instant now = Instant.now();
        // 候选查询不代表获得执行权，调用方仍需执行 claimReconciliation。
        return requiresNew(() -> store.findDueReconciliations(namespace, now, limit));
    }

    /**
     * 原子领取一条 UNKNOWN 命令进入只读对账。
     *
     * @param key 稳定命令键
     * @param leaseDuration 单次对账租约时长
     * @return 领取后的记录，竞争失败时为空
     */
    public Optional<ReliableCommandRecord> claimReconciliation(
            ReliableCommandKey key,
            Duration leaseDuration) {
        Objects.requireNonNull(key, "key");
        Duration normalizedDuration = requirePositive(leaseDuration, "leaseDuration");
        Instant now = Instant.now();
        // 对账使用新的 fencing token，过期查询结果不能覆盖后续轮次。
        return requiresNew(() -> store.claimReconciliation(
                key, workerId, now, now.plus(normalizedDuration)));
    }

    /**
     * 使用下游权威成功事实结束对账。
     *
     * @param record 已领取的对账记录
     * @param resultPayload 可重放结果
     * @param businessReference 安全业务引用
     * @param evidence 权威查询证据摘要
     * @return 当前对账租约仍有效且保存成功时返回 true
     */
    public boolean reconcileSucceeded(
            ReliableCommandRecord record,
            String resultPayload,
            String businessReference,
            String evidence) {
        ReliableCommandLease lease = requireLease(record);
        Instant now = Instant.now();
        // 权威查询结果同样受围栏保护，避免过期轮次覆盖新证据。
        return requiresNew(() -> store.reconcileSucceeded(
                record.key(), lease, resultPayload, businessReference, evidence, now));
    }

    /**
     * 使用下游权威失败事实结束对账。
     *
     * @param record 已领取的对账记录
     * @param failureCategory 稳定失败分类
     * @param failureMessage 限长失败摘要
     * @param evidence 权威查询证据摘要
     * @return 当前对账租约仍有效且保存成功时返回 true
     */
    public boolean reconcileFailed(
            ReliableCommandRecord record,
            String failureCategory,
            String failureMessage,
            String evidence) {
        ReliableCommandLease lease = requireLease(record);
        Instant now = Instant.now();
        // 明确失败必须来自权威只读查询，不能由原始远程调用的超时推断。
        return requiresNew(() -> store.reconcileFailed(
                record.key(), lease, failureCategory, failureMessage, evidence, now));
    }

    /**
     * 将本轮无结论的对账安排为重试或人工处理。
     *
     * @param record 已领取的对账记录
     * @param targetStatus UNKNOWN 或 MANUAL_REVIEW
     * @param failureCategory 稳定分类
     * @param failureMessage 限长原因摘要
     * @param evidence 本轮查询证据
     * @param nextReconcileAt 下一次查询时间，人工处理时为空
     * @return 当前对账租约仍有效且保存成功时返回 true
     */
    public boolean finishReconciliation(
            ReliableCommandRecord record,
            ReliableCommandStatus targetStatus,
            String failureCategory,
            String failureMessage,
            String evidence,
            Instant nextReconcileAt) {
        ReliableCommandLease lease = requireLease(record);
        Instant now = Instant.now();
        // 框架状态机限制目标状态，业务只决定何时停止自动查询。
        return requiresNew(() -> store.finishReconciliation(
                record.key(), lease, targetStatus, failureCategory, failureMessage,
                evidence, nextReconcileAt, now));
    }

    /**
     * 为当前 JVM 内仍在执行的远程命令批量续租。
     */
    @Scheduled(fixedDelayString = "${index12306.reliable-command.heartbeat-interval-millis:30000}")
    public void heartbeatActiveCommands() {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(executionLeaseDuration);
        for (var active : activeLeases.entrySet()) {
            // CAS 续租失败意味着记录已被恢复器接管，当前实例立即停止心跳。
            boolean renewed = requiresNew(() -> store.heartbeat(
                    active.getKey(), active.getValue(), now, leaseUntil));
            if (!renewed) {
                activeLeases.remove(active.getKey(), active.getValue());
            }
        }
    }

    /**
     * 提取记录中的完整围栏租约。
     *
     * @param record 命令记录
     * @return 可用于状态迁移的租约
     */
    private ReliableCommandLease requireLease(ReliableCommandRecord record) {
        Objects.requireNonNull(record, "record");
        return Objects.requireNonNull(record.lease(), "record lease");
    }

    /**
     * 在独立短事务中执行一次状态读写。
     *
     * @param action 状态操作
     * @param <T> 返回值类型
     * @return 非空执行结果
     */
    private <T> T requiresNew(Supplier<T> action) {
        T result = isolatedTransaction.execute(status -> action.get());
        return Objects.requireNonNull(result, "transaction result");
    }

    /**
     * 校验持续时间为正数。
     *
     * @param value 原始持续时间
     * @param fieldName 字段名称
     * @return 已校验持续时间
     */
    private Duration requirePositive(Duration value, String fieldName) {
        Duration normalized = Objects.requireNonNull(value, fieldName);
        if (normalized.isZero() || normalized.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return normalized;
    }

    /**
     * 校验实例标识为非空文本。
     *
     * @param value 原始文本
     * @param fieldName 字段名称
     * @return 去除首尾空白后的文本
     */
    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
