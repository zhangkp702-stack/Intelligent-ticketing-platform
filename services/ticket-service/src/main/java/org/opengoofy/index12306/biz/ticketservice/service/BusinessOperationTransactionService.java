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

package org.opengoofy.index12306.biz.ticketservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.BusinessOperationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.BusinessOperationMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.BusinessOperationAuditDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.BusinessOperationAuditMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 使用独立短事务认领和更新业务操作，确保购票主事务之外仍保留幂等状态。
 */
@Service
@RequiredArgsConstructor
public class BusinessOperationTransactionService {

    public static final int STATUS_PROCESSING = 0;
    public static final int STATUS_SUCCEEDED = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_UNKNOWN = 3;
    public static final int STATUS_RECONCILING = 4;
    public static final int STATUS_MANUAL_REVIEW = 5;

    private final BusinessOperationMapper businessOperationMapper;
    private final BusinessOperationAuditMapper businessOperationAuditMapper;

    /**
     * 在独立事务中认领业务操作。
     *
     * @param operation 待认领的操作记录
     * @return 插入成功返回 true，操作标识已经存在返回 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(BusinessOperationDO operation) {
        try {
            // 主键唯一约束负责在多实例并发时只允许一个请求获得执行权。
            businessOperationMapper.insert(operation);
            return true;
        } catch (DuplicateKeyException ignored) {
            // 重复请求交由上层读取原状态，当前短事务不再执行其他写操作。
            return false;
        }
    }

    /**
     * 查询已经持久化的业务操作。
     *
     * @param operationId 操作标识
     * @return 操作记录，不存在时返回 null
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public BusinessOperationDO findById(String operationId) {
        // 独立读事务确保重复插入等待结束后可以读取首个请求已经提交的状态。
        return businessOperationMapper.selectById(operationId);
    }

    /**
     * 将处理中操作更新为成功，并保存可供重复请求复用的原始结果。
     *
     * @param operationId 操作标识
     * @param leaseOwner 执行实例标识
     * @param executionEpoch 执行隔离版本
     * @param resultJson 成功结果 JSON
     * @param businessReference 订单号等安全引用
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(
            String operationId,
            String leaseOwner,
            long executionEpoch,
            String resultJson,
            String businessReference) {
        // 只允许处理中状态完成一次，避免后到请求覆盖已经确定的结果。
        int updated = businessOperationMapper.update(
                null,
                Wrappers.<BusinessOperationDO>lambdaUpdate()
                        .eq(BusinessOperationDO::getOperationId, operationId)
                        .eq(BusinessOperationDO::getStatus, STATUS_PROCESSING)
                        .eq(BusinessOperationDO::getLeaseOwner, leaseOwner)
                        .eq(BusinessOperationDO::getExecutionEpoch, executionEpoch)
                        .set(BusinessOperationDO::getStatus, STATUS_SUCCEEDED)
                        .set(BusinessOperationDO::getResultJson, resultJson)
                        .set(BusinessOperationDO::getBusinessReference, businessReference)
                        .set(BusinessOperationDO::getFailureMessage, null)
                        .set(BusinessOperationDO::getFailureCategory, null)
                        .set(BusinessOperationDO::getLeaseOwner, null)
                        .set(BusinessOperationDO::getLeaseUntil, null)
                        .set(BusinessOperationDO::getUpdateTime, new Date()));
        requireSingleUpdate(operationId, updated);
    }

    /**
     * 将无法证明下游未成功的异常调用转为 UNKNOWN 并安排只读对账。
     *
     * @param operationId 业务操作标识
     * @param leaseOwner 执行实例标识
     * @param executionEpoch 执行隔离版本
     * @param failureMessage 限长异常摘要
     * @param failureCategory 稳定未知分类
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(
            String operationId,
            String leaseOwner,
            long executionEpoch,
            String failureMessage,
            String failureCategory) {
        Date now = new Date();
        // 网络异常可能发生在下游成功之后，因此只释放租约并进入只读对账，绝不允许重放。
        int updated = businessOperationMapper.update(
                null,
                Wrappers.<BusinessOperationDO>lambdaUpdate()
                        .eq(BusinessOperationDO::getOperationId, operationId)
                        .eq(BusinessOperationDO::getStatus, STATUS_PROCESSING)
                        .eq(BusinessOperationDO::getLeaseOwner, leaseOwner)
                        .eq(BusinessOperationDO::getExecutionEpoch, executionEpoch)
                        .set(BusinessOperationDO::getStatus, STATUS_UNKNOWN)
                        .set(BusinessOperationDO::getFailureMessage, failureMessage)
                        .set(BusinessOperationDO::getFailureCategory, failureCategory)
                        .set(BusinessOperationDO::getLeaseOwner, null)
                        .set(BusinessOperationDO::getLeaseUntil, null)
                        .set(BusinessOperationDO::getNextReconcileAt, now)
                        .set(BusinessOperationDO::getUpdateTime, now));
        requireSingleUpdate(operationId, updated);
        insertAudit(operationId, leaseOwner, STATUS_PROCESSING, STATUS_UNKNOWN,
                "真实写调用结果不确定，禁止自动重放", failureCategory);
    }

    /**
     * 为当前仍生效的同步执行权延长数据库租约。
     *
     * @param operationId 业务操作标识
     * @param leaseOwner 执行实例标识
     * @param executionEpoch 执行隔离版本
     * @param heartbeatAt 心跳时间
     * @param leaseUntil 新租约截止时间
     * @return 成功续租返回 true，执行权失效返回 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean heartbeat(
            String operationId,
            String leaseOwner,
            long executionEpoch,
            Date heartbeatAt,
            Date leaseUntil) {
        // 状态、实例和 epoch 同时匹配才允许续租，旧请求不能延长新执行者的租约。
        return businessOperationMapper.update(
                null,
                Wrappers.<BusinessOperationDO>lambdaUpdate()
                        .eq(BusinessOperationDO::getOperationId, operationId)
                        .eq(BusinessOperationDO::getStatus, STATUS_PROCESSING)
                        .eq(BusinessOperationDO::getLeaseOwner, leaseOwner)
                        .eq(BusinessOperationDO::getExecutionEpoch, executionEpoch)
                        .set(BusinessOperationDO::getLastHeartbeatAt, heartbeatAt)
                        .set(BusinessOperationDO::getLeaseUntil, leaseUntil)
                        .set(BusinessOperationDO::getUpdateTime, heartbeatAt)) == 1;
    }

    /**
     * 将过期的同步执行或只读对账租约迁移为 UNKNOWN，并写入审计记录。
     *
     * @param now 当前时间
     * @param operatorId 自动恢复器标识
     * @return 成功迁移的记录数量
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverExpiredProcessing(Date now, String operatorId) {
        List<BusinessOperationDO> candidates = businessOperationMapper.selectList(
                Wrappers.<BusinessOperationDO>lambdaQuery()
                        .eq(BusinessOperationDO::getStatus, STATUS_PROCESSING)
                        .le(BusinessOperationDO::getLeaseUntil, now)
                        .orderByAsc(BusinessOperationDO::getLeaseUntil)
                        .last("LIMIT 100"));
        int recovered = 0;
        for (BusinessOperationDO candidate : candidates) {
            // 候选查询不持锁，更新语句再次匹配状态和原租约，避免覆盖刚续租的请求。
            int updated = businessOperationMapper.update(
                    null,
                    Wrappers.<BusinessOperationDO>lambdaUpdate()
                            .eq(BusinessOperationDO::getOperationId, candidate.getOperationId())
                            .eq(BusinessOperationDO::getStatus, STATUS_PROCESSING)
                            .eq(BusinessOperationDO::getExecutionEpoch, candidate.getExecutionEpoch())
                            .eq(BusinessOperationDO::getLeaseUntil, candidate.getLeaseUntil())
                            .set(BusinessOperationDO::getStatus, STATUS_UNKNOWN)
                            .set(BusinessOperationDO::getFailureCategory, "EXECUTION_LEASE_EXPIRED")
                            .set(BusinessOperationDO::getLeaseOwner, null)
                            .set(BusinessOperationDO::getLeaseUntil, null)
                            .set(BusinessOperationDO::getNextReconcileAt, now)
                            .set(BusinessOperationDO::getUpdateTime, now));
            if (updated == 1) {
                insertAudit(candidate.getOperationId(), operatorId, STATUS_PROCESSING, STATUS_UNKNOWN,
                        "同步执行租约过期，禁止重放真实写操作", null);
                recovered++;
            }
        }
        List<BusinessOperationDO> reconciliationCandidates = businessOperationMapper.selectList(
                Wrappers.<BusinessOperationDO>lambdaQuery()
                        .eq(BusinessOperationDO::getStatus, STATUS_RECONCILING)
                        .le(BusinessOperationDO::getLeaseUntil, now)
                        .orderByAsc(BusinessOperationDO::getLeaseUntil)
                        .last("LIMIT 100"));
        for (BusinessOperationDO candidate : reconciliationCandidates) {
            // 只读查询租约过期后可以安全回到 UNKNOWN，由其他实例重新查询相同事实。
            int updated = businessOperationMapper.update(
                    null,
                    Wrappers.<BusinessOperationDO>lambdaUpdate()
                            .eq(BusinessOperationDO::getOperationId, candidate.getOperationId())
                            .eq(BusinessOperationDO::getStatus, STATUS_RECONCILING)
                            .eq(BusinessOperationDO::getExecutionEpoch, candidate.getExecutionEpoch())
                            .eq(BusinessOperationDO::getLeaseUntil, candidate.getLeaseUntil())
                            .set(BusinessOperationDO::getStatus, STATUS_UNKNOWN)
                            .set(BusinessOperationDO::getLeaseOwner, null)
                            .set(BusinessOperationDO::getLeaseUntil, null)
                            .set(BusinessOperationDO::getNextReconcileAt, now)
                            .set(BusinessOperationDO::getUpdateTime, now));
            if (updated == 1) {
                insertAudit(candidate.getOperationId(), operatorId,
                        STATUS_RECONCILING, STATUS_UNKNOWN,
                        "只读对账租约过期，重新安排权威查询", null);
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * 领取一条到期 UNKNOWN 操作进入只读对账。
     *
     * @param operationId 业务操作标识
     * @param operatorId 对账实例标识
     * @param now 当前时间
     * @param leaseUntil 对账租约截止时间
     * @return 成功领取后的最新记录，竞争失败返回 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BusinessOperationDO claimReconciliation(
            String operationId,
            String operatorId,
            Date now,
            Date leaseUntil) {
        int updated = businessOperationMapper.update(
                null,
                Wrappers.<BusinessOperationDO>lambdaUpdate()
                        .eq(BusinessOperationDO::getOperationId, operationId)
                        .eq(BusinessOperationDO::getStatus, STATUS_UNKNOWN)
                        .and(wrapper -> wrapper.isNull(BusinessOperationDO::getNextReconcileAt)
                                .or().le(BusinessOperationDO::getNextReconcileAt, now))
                        .set(BusinessOperationDO::getStatus, STATUS_RECONCILING)
                        .set(BusinessOperationDO::getLeaseOwner, operatorId)
                        .set(BusinessOperationDO::getLeaseUntil, leaseUntil)
                        .setSql("execution_epoch = execution_epoch + 1, "
                                + "reconcile_attempt_count = reconcile_attempt_count + 1")
                        .set(BusinessOperationDO::getUpdateTime, now));
        if (updated != 1) {
            return null;
        }

        // 状态迁移和审计在同一事务提交，对账线程随后只执行权威只读查询。
        insertAudit(operationId, operatorId, STATUS_UNKNOWN, STATUS_RECONCILING,
                "领取下游权威状态查询", null);
        return businessOperationMapper.selectById(operationId);
    }

    /**
     * 使用下游权威成功事实结束对账。
     *
     * @param operationId 业务操作标识
     * @param resultJson 可供原接口重放的安全结果
     * @param businessReference 订单号等安全引用
     * @param operatorId 对账实例标识
     * @param evidence 下游证据摘要
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileSucceeded(
            String operationId,
            String resultJson,
            String businessReference,
            String operatorId,
            String evidence) {
        int updated = businessOperationMapper.update(
                null,
                Wrappers.<BusinessOperationDO>lambdaUpdate()
                        .eq(BusinessOperationDO::getOperationId, operationId)
                        .eq(BusinessOperationDO::getStatus, STATUS_RECONCILING)
                        .eq(BusinessOperationDO::getLeaseOwner, operatorId)
                        .set(BusinessOperationDO::getStatus, STATUS_SUCCEEDED)
                        .set(BusinessOperationDO::getResultJson, resultJson)
                        .set(BusinessOperationDO::getBusinessReference, businessReference)
                        .set(BusinessOperationDO::getFailureMessage, null)
                        .set(BusinessOperationDO::getFailureCategory, null)
                        .set(BusinessOperationDO::getNextReconcileAt, null)
                        .set(BusinessOperationDO::getLeaseOwner, null)
                        .set(BusinessOperationDO::getLeaseUntil, null)
                        .set(BusinessOperationDO::getUpdateTime, new Date()));
        requireSingleUpdate(operationId, updated);
        insertAudit(operationId, operatorId, STATUS_RECONCILING, STATUS_SUCCEEDED,
                "下游权威状态确认成功", evidence);
    }

    /**
     * 在下游仍无确定事实时安排下一次只读对账或转人工处理。
     *
     * @param operation 当前对账记录
     * @param operatorId 对账实例标识
     * @param evidence 下游证据摘要
     * @param nextRetryAt 下一次重试时间
     * @param maxAttempts 最大自动对账次数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconciliationPending(
            BusinessOperationDO operation,
            String operatorId,
            String evidence,
            Date nextRetryAt,
            int maxAttempts) {
        int nextStatus = operation.getReconcileAttemptCount() >= maxAttempts
                ? STATUS_MANUAL_REVIEW : STATUS_UNKNOWN;
        int updated = businessOperationMapper.update(
                null,
                Wrappers.<BusinessOperationDO>lambdaUpdate()
                        .eq(BusinessOperationDO::getOperationId, operation.getOperationId())
                        .eq(BusinessOperationDO::getStatus, STATUS_RECONCILING)
                        .eq(BusinessOperationDO::getLeaseOwner, operatorId)
                        .set(BusinessOperationDO::getStatus, nextStatus)
                        .set(BusinessOperationDO::getFailureCategory,
                                nextStatus == STATUS_MANUAL_REVIEW
                                        ? "RECONCILIATION_EXHAUSTED" : "DOWNSTREAM_STATUS_PENDING")
                        .set(BusinessOperationDO::getNextReconcileAt,
                                nextStatus == STATUS_UNKNOWN ? nextRetryAt : null)
                        .set(BusinessOperationDO::getLeaseOwner, null)
                        .set(BusinessOperationDO::getLeaseUntil, null)
                        .set(BusinessOperationDO::getUpdateTime, new Date()));
        requireSingleUpdate(operation.getOperationId(), updated);
        insertAudit(operation.getOperationId(), operatorId, STATUS_RECONCILING, nextStatus,
                nextStatus == STATUS_MANUAL_REVIEW ? "自动对账次数耗尽" : "下游尚无确定事实", evidence);
    }

    /**
     * 查询当前到期的 UNKNOWN 操作候选。
     *
     * @param now 当前时间
     * @return 最多一百条候选记录
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<BusinessOperationDO> findDueReconciliations(Date now) {
        return businessOperationMapper.selectList(
                Wrappers.<BusinessOperationDO>lambdaQuery()
                        .eq(BusinessOperationDO::getStatus, STATUS_UNKNOWN)
                        .and(wrapper -> wrapper.isNull(BusinessOperationDO::getNextReconcileAt)
                                .or().le(BusinessOperationDO::getNextReconcileAt, now))
                        .orderByAsc(BusinessOperationDO::getNextReconcileAt)
                        .last("LIMIT 100"));
    }

    /**
     * 写入不包含敏感业务正文的状态迁移审计。
     *
     * @param operationId 业务操作标识
     * @param operatorId 操作人或恢复器
     * @param oldStatus 原状态
     * @param newStatus 新状态
     * @param reason 迁移原因
     * @param evidence 安全证据摘要
     */
    private void insertAudit(
            String operationId,
            String operatorId,
            int oldStatus,
            int newStatus,
            String reason,
            String evidence) {
        businessOperationAuditMapper.insert(BusinessOperationAuditDO.builder()
                .operationId(operationId)
                .operatorId(Objects.requireNonNull(operatorId, "operatorId"))
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .reason(reason)
                .evidence(evidence)
                .build());
    }

    /**
     * 校验幂等状态仅发生一次有效迁移。
     *
     * @param operationId 操作标识
     * @param updated 实际更新行数
     */
    private void requireSingleUpdate(String operationId, int updated) {
        if (updated != 1) {
            throw new IllegalStateException("业务操作状态更新失败: " + operationId);
        }
    }
}
