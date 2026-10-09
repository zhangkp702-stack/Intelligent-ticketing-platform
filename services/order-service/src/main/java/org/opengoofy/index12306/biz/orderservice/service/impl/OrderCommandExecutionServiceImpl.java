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

package org.opengoofy.index12306.biz.orderservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCommandStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCommandMapper;
import org.opengoofy.index12306.biz.orderservice.service.OrderCommandExecutionService;
import org.opengoofy.index12306.biz.orderservice.service.OrderService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通过数据库租约执行订单创建 Command，并按明确恢复分片轮询遗漏任务。
 */
@Slf4j
@Service
public class OrderCommandExecutionServiceImpl implements OrderCommandExecutionService {

    private static final int COMMAND_SHARD_COUNT = 32;

    private final OrderCommandMapper orderCommandMapper;
    private final OrderService orderService;
    private final Executor orderCommandExecutor;
    private final AtomicInteger recoveryCursor = new AtomicInteger(
            ThreadLocalRandom.current().nextInt(COMMAND_SHARD_COUNT));
    private final String executorInstanceId = "order-command-" + UUID.randomUUID();

    @Value("${index12306.order.command.claim-lease-millis:120000}")
    private long claimLeaseMillis;

    @Value("${index12306.order.command.recovery-shards-per-run:4}")
    private int recoveryShardsPerRun;

    @Value("${index12306.order.command.recovery-batch-size-per-shard:32}")
    private int recoveryBatchSizePerShard;

    @Value("${index12306.order.command.unknown-recheck-millis:300000}")
    private long unknownRecheckMillis;

    /**
     * 创建订单任务执行服务并绑定专用有界线程池。
     *
     * @param orderCommandMapper Command 持久层
     * @param orderService 订单事务执行服务
     * @param orderCommandExecutor 订单任务专用执行器
     */
    public OrderCommandExecutionServiceImpl(
            OrderCommandMapper orderCommandMapper,
            OrderService orderService,
            @Qualifier("orderCommandExecutor") Executor orderCommandExecutor) {
        // 显式限定执行器，防止订单写库占用 MQ 消费线程或 Spring 调度线程。
        this.orderCommandMapper = orderCommandMapper;
        this.orderService = orderService;
        this.orderCommandExecutor = orderCommandExecutor;
    }

    /**
     * 尝试异步执行刚持久化的 Command，线程池拒绝时保留数据库任务等待恢复扫描。
     *
     * @param userId 用户分片键
     * @param reservationId 稳定购票受理标识
     */
    @Override
    public void dispatchAsync(String userId, String reservationId) {
        // 即时任务只携带稳定路由键，不在内存队列复制包含乘客隐私的请求载荷。
        submit(() -> executeOne(userId, reservationId), userId, reservationId);
    }

    /**
     * 以轮转游标扫描有限数量的明确物理分片，恢复进程退出、线程池拒绝和租约过期任务。
     */
    @Scheduled(
            fixedDelayString = "${index12306.order.command.recovery-interval-millis:500}",
            initialDelayString = "${index12306.order.command.recovery-initial-delay-millis:5000}")
    public void recoverDueCommands() {
        int shardRuns = Math.min(Math.max(recoveryShardsPerRun, 1), COMMAND_SHARD_COUNT);
        Date now = new Date();
        // 每轮只读取部分分片并由游标轮转，限制恢复查询 QPS，同时保证所有分片最终都会被覆盖。
        for (int index = 0; index < shardRuns; index++) {
            int recoveryShard = Math.floorMod(recoveryCursor.getAndIncrement(), COMMAND_SHARD_COUNT);
            try {
                // 单个物理分片暂时不可用时继续扫描其他分片，避免一次异常中断整轮恢复。
                List<OrderCommandDO> candidates = findRecoveryCandidates(recoveryShard, now);
                candidates.forEach(this::dispatchRecoveryCandidate);
            } catch (RuntimeException exception) {
                log.error("order_command_recovery_shard_failed recoveryShard={}", recoveryShard, exception);
            }
        }
    }

    /**
     * 按 recovery_shard 等值条件读取一个物理表中的到期任务。
     *
     * @param recoveryShard 物理恢复分片编号
     * @param now 本轮数据库时间上界
     * @return 待执行、租约过期或等待对账的候选任务
     */
    private List<OrderCommandDO> findRecoveryCandidates(int recoveryShard, Date now) {
        QueryWrapper<OrderCommandDO> query = Wrappers.<OrderCommandDO>query()
                // 恢复列表不读取加密请求正文，只有成功持有执行租约的工作线程才解密载荷。
                .select("user_id", "recovery_shard", "request_fingerprint", "reservation_id", "status",
                        "attempt_count", "max_attempts", "lease_token", "lease_expire_time", "next_retry_time")
                .eq("recovery_shard", recoveryShard)
                .and(scope -> scope
                        .nested(ready -> ready
                                .in("status", OrderCommandStatusEnum.RECEIVED.name(),
                                        OrderCommandStatusEnum.RETRY_WAIT.name())
                                .le("next_retry_time", now))
                        .or(expired -> expired
                                .eq("status", OrderCommandStatusEnum.PROCESSING.name())
                                .le("lease_expire_time", now))
                        .or(unknown -> unknown
                                .eq("status", OrderCommandStatusEnum.UNKNOWN.name())
                                .le("next_retry_time", now)))
                .orderByAsc("next_retry_time", "id")
                .last("LIMIT " + Math.max(1, recoveryBatchSizePerShard));
        return orderCommandMapper.selectList(query);
    }

    /**
     * 将恢复候选提交到有界执行器，UNKNOWN 只做订单事实对账，不直接再次创建订单。
     *
     * @param command 恢复扫描返回的候选任务
     */
    private void dispatchRecoveryCandidate(OrderCommandDO command) {
        if (OrderCommandStatusEnum.UNKNOWN.name().equals(command.getStatus())) {
            // UNKNOWN 可能已经提交订单，只允许先查询订单事实并补齐成功终态。
            submit(() -> orderService.reconcileUnknownOrderCommand(command),
                    command.getUserId(), command.getReservationId());
            return;
        }
        submit(() -> executeOne(command.getUserId(), command.getReservationId()),
                command.getUserId(), command.getReservationId());
    }

    /**
     * 原子认领一个可执行任务，再把带租约围栏和解密载荷的记录交给订单事务。
     *
     * @param userId 用户分片键
     * @param reservationId 稳定购票受理标识
     */
    private void executeOne(String userId, String reservationId) {
        OrderCommandDO claimed = claimCommand(userId, reservationId);
        if (claimed == null) {
            // 最后一次执行期间宕机时没有机会写 UNKNOWN，此处补齐安全终态但绝不发布失败结果。
            markExhaustedCommandUnknown(userId, reservationId);
            return;
        }
        try {
            // 订单、Command 成功状态和结果 Outbox 由订单服务在同一事务内提交。
            orderService.executeClaimedOrderCommand(claimed);
        } catch (RuntimeException exception) {
            // 状态收敛失败时保留 PROCESSING，租约过期后会再次恢复，不能在内存中吞掉任务。
            log.error("order_command_execution_state_not_persisted userId={}, reservationId={}, leaseToken={}",
                    userId, reservationId, claimed.getLeaseToken(), exception);
        }
    }

    /**
     * 使用条件更新竞争执行租约，并读取当前围栏令牌及加密请求载荷。
     *
     * @param userId 用户分片键
     * @param reservationId 稳定购票受理标识
     * @return 当前实例认领成功的完整 Command，竞争失败返回 null
     */
    private OrderCommandDO claimCommand(String userId, String reservationId) {
        Date now = new Date();
        Date leaseExpireTime = new Date(now.getTime() + Math.max(1L, claimLeaseMillis));
        String claimOwner = executorInstanceId + "-" + UUID.randomUUID();
        UpdateWrapper<OrderCommandDO> claim = Wrappers.<OrderCommandDO>update()
                .set("status", OrderCommandStatusEnum.PROCESSING.name())
                .set("lease_owner", claimOwner)
                .set("lease_expire_time", leaseExpireTime)
                .set("failure_reason", null)
                .setSql("attempt_count = attempt_count + 1")
                .setSql("lease_token = lease_token + 1")
                .eq("user_id", userId)
                .eq("reservation_id", reservationId)
                .apply("attempt_count < max_attempts")
                .and(scope -> scope
                        .nested(ready -> ready
                                .in("status", OrderCommandStatusEnum.RECEIVED.name(),
                                        OrderCommandStatusEnum.RETRY_WAIT.name())
                                .le("next_retry_time", now))
                        .or(expired -> expired
                                .eq("status", OrderCommandStatusEnum.PROCESSING.name())
                                .le("lease_expire_time", now)));
        // 条件更新是多实例唯一执行权；迟到线程后续还必须用递增 token 完成成功状态更新。
        if (orderCommandMapper.update(null, claim) != 1) {
            return null;
        }
        return orderCommandMapper.selectOne(Wrappers.<OrderCommandDO>query()
                .eq("user_id", userId)
                .eq("reservation_id", reservationId)
                .eq("status", OrderCommandStatusEnum.PROCESSING.name())
                .eq("lease_owner", claimOwner));
    }

    /**
     * 把已耗尽次数且执行租约过期的任务转为 UNKNOWN，等待人工或订单事实对账。
     *
     * @param userId 用户分片键
     * @param reservationId 稳定购票受理标识
     */
    private void markExhaustedCommandUnknown(String userId, String reservationId) {
        Date now = new Date();
        Date nextCheckTime = new Date(now.getTime() + Math.max(1L, unknownRecheckMillis));
        // UNKNOWN 不产生失败结果，避免数据库提交结果不明时错误释放已经售出的座位。
        orderCommandMapper.update(null, Wrappers.<OrderCommandDO>update()
                .set("status", OrderCommandStatusEnum.UNKNOWN.name())
                .set("next_retry_time", nextCheckTime)
                .set("lease_owner", null)
                .set("lease_expire_time", null)
                .set("failure_reason", "ATTEMPTS_EXHAUSTED")
                .eq("user_id", userId)
                .eq("reservation_id", reservationId)
                .apply("attempt_count >= max_attempts")
                .and(scope -> scope
                        .nested(ready -> ready.in("status", OrderCommandStatusEnum.RECEIVED.name(),
                                OrderCommandStatusEnum.RETRY_WAIT.name()))
                        .or(expired -> expired
                                .eq("status", OrderCommandStatusEnum.PROCESSING.name())
                                .le("lease_expire_time", now))));
    }

    /**
     * 向有界线程池提交任务，拒绝时只记录路由标识并等待下轮数据库恢复。
     *
     * @param task 不包含敏感载荷的执行任务
     * @param userId 用户分片键
     * @param reservationId 稳定购票受理标识
     */
    private void submit(Runnable task, String userId, String reservationId) {
        try {
            // 拒绝策略不会阻塞 MQ 或扫描线程，数据库 Command 仍保留恢复所需的完整状态。
            orderCommandExecutor.execute(task);
        } catch (RuntimeException exception) {
            log.warn("order_command_executor_rejected userId={}, reservationId={}", userId, reservationId, exception);
        }
    }
}
