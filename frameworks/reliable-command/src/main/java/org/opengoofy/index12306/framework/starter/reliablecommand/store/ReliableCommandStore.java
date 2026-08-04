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

import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandClaim;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandDefinition;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 可靠命令的持久化和围栏状态迁移接口。
 *
 * <p>实现必须使用当前服务或当前分片的数据源，以便本地业务副作用可以和命令记录加入同一事务。</p>
 */
public interface ReliableCommandStore {

    /**
     * 首次插入命令并获得执行租约，重复命令返回当前权威状态。
     *
     * @param definition 命令定义
     * @param leaseOwner 执行实例标识
     * @param now 当前时间
     * @param leaseUntil 租约截止时间
     * @return 认领或重复请求判定结果
     */
    ReliableCommandClaim claim(
            ReliableCommandDefinition definition,
            String leaseOwner,
            Instant now,
            Instant leaseUntil);

    /**
     * 查询命令当前状态。
     *
     * @param key 命令键
     * @return 命令记录，不存在时为空
     */
    Optional<ReliableCommandRecord> find(ReliableCommandKey key);

    /**
     * 统计指定命令域中某个可靠状态的记录数，用于低频健康巡检和告警，不参与业务决策。
     *
     * @param namespace 命令业务域
     * @param status 待统计的可靠命令状态
     * @return 当前状态记录数
     */
    long countByStatus(String namespace, ReliableCommandStatus status);

    /**
     * 为仍由当前实例持有的执行权续租。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param heartbeatAt 心跳时间
     * @param leaseUntil 新租约截止时间
     * @return 续租成功返回 true
     */
    boolean heartbeat(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            Instant heartbeatAt,
            Instant leaseUntil);

    /**
     * 使用当前围栏租约将真实业务执行标记为成功。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param resultPayload 可复用的序列化结果
     * @param businessReference 安全业务引用
     * @param completedAt 完成时间
     * @return 状态成功迁移返回 true
     */
    boolean markSucceeded(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            String resultPayload,
            String businessReference,
            Instant completedAt);

    /**
     * 将可以确定未产生未知副作用的命令标记为失败。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param failureCategory 稳定失败分类
     * @param failureMessage 限长故障摘要
     * @param completedAt 完成时间
     * @return 状态成功迁移返回 true
     */
    boolean markFailed(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            String failureCategory,
            String failureMessage,
            Instant completedAt);

    /**
     * 将无法判断远程副作用结果的命令转为未知状态。
     *
     * @param key 命令键
     * @param lease 当前租约
     * @param failureCategory 未知原因分类
     * @param failureMessage 限长故障摘要
     * @param nextReconcileAt 下一次权威查询时间
     * @param changedAt 状态迁移时间
     * @return 状态成功迁移返回 true
     */
    boolean markUnknown(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            String failureCategory,
            String failureMessage,
            Instant nextReconcileAt,
            Instant changedAt);

    /**
     * 将租约已过期的真实执行或只读对账转为未知状态。
     *
     * @param now 当前时间
     * @param operatorId 恢复器实例标识
     * @param limit 单批处理上限
     * @return 成功恢复的记录数量
     */
    int recoverExpiredLeases(String namespace, Instant now, String operatorId, int limit);

    /**
     * 查询已经到达对账时间的未知命令。
     *
     * @param namespace 只查询指定命令域
     * @param now 当前时间
     * @param limit 单批查询上限
     * @return 待对账命令
     */
    List<ReliableCommandRecord> findDueReconciliations(String namespace, Instant now, int limit);

    /**
     * 原子领取未知命令进入只读对账并递增围栏令牌。
     *
     * @param key 命令键
     * @param operatorId 对账实例标识
     * @param now 当前时间
     * @param leaseUntil 对账租约截止时间
     * @return 领取后的记录，竞争失败时为空
     */
    Optional<ReliableCommandRecord> claimReconciliation(
            ReliableCommandKey key,
            String operatorId,
            Instant now,
            Instant leaseUntil);

    /**
     * 使用权威下游成功事实结束对账。
     *
     * @param key 命令键
     * @param lease 当前对账租约
     * @param resultPayload 可复用结果
     * @param businessReference 安全业务引用
     * @param evidence 权威查询证据摘要
     * @param completedAt 完成时间
     * @return 状态成功迁移返回 true
     */
    boolean reconcileSucceeded(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            String resultPayload,
            String businessReference,
            String evidence,
            Instant completedAt);

    /**
     * 使用下游权威失败事实结束对账。
     *
     * @param key 命令键
     * @param lease 当前对账租约
     * @param failureCategory 稳定失败分类
     * @param failureMessage 限长失败摘要
     * @param evidence 权威查询证据摘要
     * @param completedAt 完成时间
     * @return 状态成功迁移返回 true
     */
    boolean reconcileFailed(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            String failureCategory,
            String failureMessage,
            String evidence,
            Instant completedAt);

    /**
     * 在权威查询仍无结论时安排下一次查询或转人工处理。
     *
     * @param key 命令键
     * @param lease 当前对账租约
     * @param targetStatus 只允许 UNKNOWN 或 MANUAL_REVIEW
     * @param failureCategory 稳定原因分类
     * @param failureMessage 限长原因摘要
     * @param evidence 权威查询证据摘要
     * @param nextReconcileAt 下一次查询时间，转人工时为空
     * @param changedAt 状态迁移时间
     * @return 状态成功迁移返回 true
     */
    boolean finishReconciliation(
            ReliableCommandKey key,
            ReliableCommandLease lease,
            ReliableCommandStatus targetStatus,
            String failureCategory,
            String failureMessage,
            String evidence,
            Instant nextReconcileAt,
            Instant changedAt);

    /**
     * 由受权人工操作重新开启已耗尽的只读对账，不会重新执行原始业务写命令。
     *
     * @param key 命令键
     * @param operatorId 人工操作员标识
     * @param reason 人工重新核对原因
     * @param nextReconcileAt 下一次权威查询时间
     * @param changedAt 状态迁移时间
     * @return 成功从 MANUAL_REVIEW 迁移到 UNKNOWN 时返回 true
     */
    boolean resumeManualReview(
            ReliableCommandKey key,
            String operatorId,
            String reason,
            Instant nextReconcileAt,
            Instant changedAt);

    /**
     * 查询命令的状态迁移审计。
     *
     * @param key 命令键
     * @return 按发生时间排序的审计记录
     */
    List<ReliableCommandAuditRecord> findAudit(ReliableCommandKey key);
}
