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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketSeatReservationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketSeatReservationMapper;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatLease;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.RedisSeatLeaseRecoveryMetrics;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.RedisSeatLeaseRecoveryMetrics.RecoveryOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 回收 Redis 原子预占但尚未获得 MySQL 确认的短期座位租约。
 *
 * <p>租约元数据与 bitmap/owner 由同一个 Lua 写入；因此即使进程在将 INIT 更新为 PREPARED 前宕机，
 * 扫描器仍能根据租约精确释放原 owner 的 bit。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSeatLeaseRecoveryService {

    private final RedisSeatBitmapService redisSeatBitmapService;
    private final TicketSeatReservationMapper ticketSeatReservationMapper;
    private final TicketSeatReservationReleaseService ticketSeatReservationReleaseService;
    private final RedisSeatLeaseRecoveryMetrics redisSeatLeaseRecoveryMetrics;

    @Value("${index12306.ticket.redis-seat-lease.enabled:true}")
    private boolean redisSeatLeaseRecoveryEnabled;

    @Value("${index12306.ticket.redis-seat-lease.batch-size:100}")
    private int redisSeatLeaseRecoveryBatchSize;

    /**
     * 扫描过期租约：已进入 PREPARED/BOUND 的记录只确认租约，其余记录按 owner 精确释放并推进本地补偿。
     */
    @Scheduled(fixedDelayString = "${index12306.ticket.redis-seat-lease.recovery-interval-millis:5000}")
    public void recoverExpiredLeases() {
        if (!redisSeatLeaseRecoveryEnabled) {
            return;
        }
        int batchSize = Math.max(1, redisSeatLeaseRecoveryBatchSize);
        List<RedisSeatLease> leases;
        try {
            // Redis 扫描失败时不把异常扩散到调度线程，保留下一轮按租约重新读取的机会。
            leases = redisSeatBitmapService.listExpiredLeases(batchSize);
        } catch (RuntimeException ex) {
            recordScanFailureSafely(ex);
            return;
        }
        if (leases == null) {
            refreshExpiredLeaseBatchSafely(0);
            return;
        }
        refreshExpiredLeaseBatchSafely(leases.size());
        // 每个租约独立执行；单条租约异常不阻塞下一条过期 owner 的释放。
        leases.forEach(this::recoverLease);
    }

    /**
     * 依据 reservation 的持久化阶段决定过期租约应被确认还是释放。
     *
     * @param lease 当前 Redis 租约上下文
     */
    private void recoverLease(RedisSeatLease lease) {
        long startedNanos = System.nanoTime();
        RecoveryOutcome outcome = RecoveryOutcome.FAILED;
        try {
            TicketSeatReservationDO reservation = ticketSeatReservationMapper
                    .selectByReservationId(lease.getReservationId());
            if (reservation != null && isDatabaseSeatCommitted(reservation.getReservationStatus())) {
                // 订单建单事务已经完成，Redis 位图需要继续作为快速冲突屏障，只移除短期处理租约。
                redisSeatBitmapService.confirmLease(lease);
                outcome = RecoveryOutcome.CONFIRMED;
                return;
            }
            // 租约到期即表示该 owner 失去继续占位权；Lua 会再次校验 owner，绝不清理新请求的座位。
            redisSeatBitmapService.releaseExpiredLease(lease);
            if (reservation != null && isUnprepared(reservation.getReservationStatus())) {
                // 同步推进 INIT 的库存补偿；Redis 已由上一步按租约上下文清理。
                ticketSeatReservationReleaseService.releaseUnpreparedReservation(lease.getReservationId());
            }
            outcome = RecoveryOutcome.RELEASED;
        } catch (RuntimeException ex) {
            log.error("ticket_redis_seat_lease_recovery_failed reservationId={}", lease.getReservationId(), ex);
        } finally {
            recordRecoverySafely(outcome, startedNanos);
        }
    }

    /**
     * 记录单轮发现的过期租约数量，指标异常不影响后续逐条恢复。
     *
     * @param count 当前扫描批次的过期租约数量
     */
    private void refreshExpiredLeaseBatchSafely(int count) {
        try {
            redisSeatLeaseRecoveryMetrics.refreshExpiredLeaseBatch(count);
        } catch (RuntimeException ex) {
            log.warn("ticket_redis_seat_lease_metrics_refresh_failed exceptionType={}",
                    ex.getClass().getSimpleName());
        }
    }

    /**
     * 记录单条租约恢复结果，指标故障不能中断座位回收。
     *
     * @param outcome 当前租约的处理结果
     * @param startedNanos 当前租约处理开始时间
     */
    private void recordRecoverySafely(RecoveryOutcome outcome, long startedNanos) {
        try {
            redisSeatLeaseRecoveryMetrics.recordRecovery(outcome, startedNanos);
        } catch (RuntimeException ex) {
            log.warn("ticket_redis_seat_lease_metrics_record_failed outcome={}, exceptionType={}", outcome,
                    ex.getClass().getSimpleName());
        }
    }

    /**
     * 记录 Redis 扫描基础设施失败，调度任务将在下一轮重新尝试。
     *
     * @param exception 当前扫描异常
     */
    private void recordScanFailureSafely(RuntimeException exception) {
        try {
            redisSeatLeaseRecoveryMetrics.recordScanFailure();
        } catch (RuntimeException metricsException) {
            log.warn("ticket_redis_seat_lease_metrics_scan_failure_record_failed exceptionType={}",
                    metricsException.getClass().getSimpleName());
        }
        log.warn("ticket_redis_seat_lease_scan_failed exceptionType={}", exception.getClass().getSimpleName());
    }

    /**
     * 判断订单本地锁座及建单 Outbox 是否已提交。
     *
     * @param reservationStatus 当前 reservation 状态
     * @return 已提交时返回 true
     */
    private boolean isDatabaseSeatCommitted(Integer reservationStatus) {
        return reservationStatus != null && (reservationStatus == TicketSeatReservationReleaseService.RESERVATION_PREPARED
                || reservationStatus == TicketSeatReservationReleaseService.RESERVATION_BOUND);
    }

    /**
     * 判断是否仍可通过预备阶段补偿安全中止。
     *
     * @param reservationStatus 当前 reservation 状态
     * @return INIT、历史 REDIS_HELD 或 ABORTING 时返回 true
     */
    private boolean isUnprepared(Integer reservationStatus) {
        return reservationStatus != null && (reservationStatus == TicketSeatReservationReleaseService.RESERVATION_INIT
                || reservationStatus == TicketSeatReservationReleaseService.RESERVATION_REDIS_HELD_LEGACY
                || reservationStatus == TicketSeatReservationReleaseService.RESERVATION_ABORTING);
    }
}
