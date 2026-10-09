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
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.OrderCommandStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketSeatReservationRecoveryMetrics;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketSeatReservationRecoveryMetrics.RecoveryFlow;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketSeatReservationRecoveryMetrics.RecoveryOutcome;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 扫描预备阶段、已绑定订单的释放缺口与 PREPARED 建单结果，并按各阶段权威事实推进恢复。
 *
 * <p>已绑定订单只根据关闭状态重放可靠回滚；PREPARED 记录只接受稳定命令的 SUCCEEDED 或 FAILED 终态，
 * 以避免根据本地超时猜测远程建单结果。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketSeatReservationRecoveryService {

    private static final int ORDER_STATUS_CLOSED = 30;
    private static final int RESERVATION_RELEASING = 2;
    private static final String ORDER_COMMAND_SUCCEEDED = "SUCCEEDED";
    private static final String ORDER_COMMAND_FAILED = "FAILED";

    private final TicketSeatReservationMapper ticketSeatReservationMapper;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final OrderCloseRollbackService orderCloseRollbackService;
    private final TicketSeatReservationReleaseService ticketSeatReservationReleaseService;
    private final TicketSeatReservationRecoveryMetrics ticketSeatReservationRecoveryMetrics;

    @Value("${index12306.ticket.reservation-recovery.timeout-millis:60000}")
    private long reservationRecoveryTimeoutMillis;

    @Value("${index12306.ticket.reservation-recovery.batch-size:100}")
    private int reservationRecoveryBatchSize;

    @Value("${index12306.ticket.reservation-recovery.loadtest-orphan-release.enabled:false}")
    private boolean loadTestOrphanReleaseEnabled;

    /**
     * 定时扫描购票各持久化阶段，分别推进预备失败回收、关闭回滚和建单命令对账。
     */
    @Scheduled(fixedDelayString = "${index12306.ticket.reservation-recovery.interval-millis:5000}")
    public void recoverStaleReservations() {
        // PREPARED 之前没有建单命令，先通过本地 fencing 状态回收超时的 INIT 与历史 REDIS_HELD。
        recoverStaleUnpreparedReservations();
        // 已绑定订单与待绑定订单使用不同的权威事实，必须分两条恢复链路处理。
        recoverStaleClosedReservations();
        // 仅在隔离压测环境显式开启时回收没有 Outbox 的历史测试孤儿记录。
        recoverStaleLoadTestPreparedReservationsWithoutOutbox();
        recoverStalePreparedReservations();
    }

    /**
     * 扫描超时的 INIT、历史 REDIS_HELD 或 ABORTING，并通过状态领取后幂等释放本地资源。
     */
    public void recoverStaleUnpreparedReservations() {
        // 宽限期保证正常请求有足够时间把预备状态推进到 PREPARED，领取后原请求将被 fencing 拒绝。
        Date deadline = new Date(System.currentTimeMillis() - Math.max(1L, reservationRecoveryTimeoutMillis));
        int batchSize = Math.max(1, reservationRecoveryBatchSize);
        List<TicketSeatReservationDO> reservations = ticketSeatReservationMapper
                .selectStaleUnpreparedReservations(deadline, batchSize);
        if (reservations == null || reservations.isEmpty()) {
            return;
        }
        // 每条记录独立推进，单条 Redis 或库存异常只保留 ABORTING 等待下一轮重试。
        reservations.forEach(reservation -> {
            try {
                ticketSeatReservationReleaseService.releaseUnpreparedReservation(reservation.getReservationId());
            } catch (RuntimeException ex) {
                log.error("ticket_unprepared_reservation_recovery_failed reservationId={}, status={}",
                        reservation.getReservationId(), reservation.getReservationStatus(), ex);
            }
        });
    }

    /**
     * 回收压测账号遗留的、未进入建单 Outbox 的超时 PREPARED 座位。
     *
     * <p>该分支只处理 loadtest 前缀、三类释放步骤均未开始且缺少 Outbox 的历史测试数据；
     * 正常用户和任何已进入可靠建单流程的记录都不会被选中。</p>
     */
    public void recoverStaleLoadTestPreparedReservationsWithoutOutbox() {
        // 默认关闭，避免生产环境把未知 PREPARED 记录误判为可释放的测试数据。
        if (!loadTestOrphanReleaseEnabled) {
            return;
        }
        // 与常规恢复共用宽限期和批次上限，避免刚提交的请求被本次测试清理误处理。
        Date deadline = new Date(System.currentTimeMillis() - Math.max(1L, reservationRecoveryTimeoutMillis));
        int batchSize = Math.max(1, reservationRecoveryBatchSize);
        List<TicketSeatReservationDO> reservations = ticketSeatReservationMapper
                .selectStaleLoadTestPreparedReservationsWithoutOutbox(deadline, batchSize);
            // 没有符合严格条件的历史测试孤儿时不触发任何座位、Redis 或库存操作。
        if (reservations == null || reservations.isEmpty()) {
            return;
        }
        // 输出本批次规模，便于隔离压测环境确认清理任务已启动并持续收敛。
        log.info("ticket_loadtest_orphan_reservation_release_started count={}", reservations.size());
            // 复用正式失败释放器，确保数据库占座、Redis owner 位图和统一库存按 reservationId 幂等回滚。
        reservations.forEach(reservation -> {
            try {
                ticketSeatReservationReleaseService.releasePreparedReservation(reservation.getReservationId());
            } catch (RuntimeException ex) {
                // 单条释放异常保留 RELEASING 状态，下一轮仍会通过同一 reservationId 继续补偿。
                log.error("ticket_loadtest_orphan_reservation_release_failed reservationId={}, username={}",
                        reservation.getReservationId(), reservation.getUsername(), ex);
            }
        });
    }

    /**
     * 扫描超时且释放步骤未完成的已绑定 reservation，补偿 Canal 事件丢失或进程中断后的资源释放。
     */
    public void recoverStaleClosedReservations() {
        // 仅挑选超过恢复宽限期仍未完成的记录，避免与刚提交的正常关闭流程争抢执行权。
        Date deadline = new Date(System.currentTimeMillis() - Math.max(1L, reservationRecoveryTimeoutMillis));
        int batchSize = Math.max(1, reservationRecoveryBatchSize);
        List<TicketSeatReservationDO> reservations = ticketSeatReservationMapper
                .selectStaleIncompleteReservations(deadline, batchSize);
        // 空批次直接返回，避免对订单服务产生无意义的查询压力。
        if (reservations == null || reservations.isEmpty()) {
            return;
        }
        // 每条 reservation 都单独核对订单状态，单条远程失败不能阻断同批其他恢复任务。
        reservations.forEach(this::recoverClosedReservation);
    }

    /**
     * 扫描超时 PREPARED reservation，并仅根据订单服务持久化命令终态补绑或失败释放。
     */
    public void recoverStalePreparedReservations() {
        // 为刚发出的建单请求保留宽限期，避免与同步成功返回和正常绑定流程竞争。
        Date deadline = new Date(System.currentTimeMillis() - Math.max(1L, reservationRecoveryTimeoutMillis));
        int batchSize = Math.max(1, reservationRecoveryBatchSize);
        List<TicketSeatReservationDO> reservations = ticketSeatReservationMapper
                .selectStalePreparedReservations(deadline, batchSize);
        // 空批次不访问订单服务，避免闲时持续产生远程请求。
        if (reservations == null || reservations.isEmpty()) {
            return;
        }
        // 每条 reservation 单独查询稳定命令，单条异常不能阻断同批其他用户的恢复。
        reservations.forEach(this::recoverPreparedReservation);
    }

    /**
     * 在订单已关闭时重放该订单的可靠回滚命令；未关闭或状态未知时保持 reservation 原状。
     *
     * @param reservation 待核对的超时座位占用记录
     */
    private void recoverClosedReservation(TicketSeatReservationDO reservation) {
        long startedNanos = System.nanoTime();
        RecoveryOutcome outcome = RecoveryOutcome.EXECUTION_FAILED;
        try {
            // 新记录的订单号等于 reservationId；仅灰度期历史记录仍需使用旧的 order_sn 查询。
            String orderSn = reservation.getOrderSn() == null || reservation.getOrderSn().isBlank()
                    ? reservation.getReservationId() : reservation.getOrderSn();
            // 订单服务是是否可以归还真实库存的权威来源，不能仅依赖本地超时判断。
            Result<TicketOrderDetailRespDTO> result = ticketOrderRemoteService
                    .queryTicketOrderByOrderSn(orderSn);
            if (!result.isSuccess() || result.getData() == null) {
                outcome = RecoveryOutcome.ORDER_QUERY_FAILED;
                log.warn("ticket_reservation_recovery_order_query_failed reservationId={}",
                        reservation.getReservationId());
                return;
            }
            // 待支付、已支付等状态仍然持有真实库存，扫描器不得提前释放。
            if (!Integer.valueOf(ORDER_STATUS_CLOSED).equals(result.getData().getStatus())) {
                outcome = RecoveryOutcome.ORDER_NOT_CLOSED;
                return;
            }
            // 重用关闭回滚可靠命令，由其按 reservation 的分步骤状态幂等推进释放。
            orderCloseRollbackService.rollback(orderSn);
            outcome = RecoveryOutcome.ORDER_CLOSED;
        } catch (RuntimeException ex) {
            // 保留未完成状态供下一轮重试；日志携带人工排查所需的各资源步骤状态。
            log.error("ticket_reservation_recovery_failed reservationId={}, dbStatus={}, redisStatus={}, inventoryStatus={}",
                    reservation.getReservationId(), reservation.getDbSeatReleaseStatus(),
                    reservation.getRedisBitmapReleaseStatus(), reservation.getTokenRollbackStatus(), ex);
        } finally {
            // 指标记录失败不能影响恢复正确性，只能作为监控采集故障单独告警。
            recordRecoverySafely(RecoveryFlow.CLOSED_ORDER, outcome, startedNanos);
        }
    }

    /**
     * 按 reservation 保存的原用户身份查询订单命令，并对明确成功或失败的结果执行幂等收敛。
     *
     * @param reservation 待对账的 PREPARED 或 RELEASING 座位占用记录
     */
    private void recoverPreparedReservation(TicketSeatReservationDO reservation) {
        long startedNanos = System.nanoTime();
        RecoveryOutcome outcome = RecoveryOutcome.EXECUTION_FAILED;
        if (reservation.getReservationId() == null || reservation.getReservationId().isBlank()
                || reservation.getUserId() == null || reservation.getUserId().isBlank()) {
            // 缺少命令或用户分片键无法进行有归属校验的远程查询，只能保留记录等待人工处理。
            log.error("ticket_prepared_reservation_missing_reconcile_key reservationId={}, userId={}",
                    reservation.getReservationId(), reservation.getUserId());
            recordRecoverySafely(RecoveryFlow.PREPARED_COMMAND, RecoveryOutcome.MISSING_RECONCILE_KEY, startedNanos);
            return;
        }
        UserInfoDTO previousUser = currentUser();
        try {
            // 恢复原用户身份，让订单服务按正确分片和订单归属查询稳定命令。
            UserContext.setUser(UserInfoDTO.builder()
                    .userId(reservation.getUserId())
                    .username(reservation.getUsername())
                    .build());
            Result<OrderCommandStatusRespDTO> result = ticketOrderRemoteService
                    .queryReservationOrderStatus(reservation.getReservationId());
            if (!result.isSuccess() || result.getData() == null) {
                outcome = RecoveryOutcome.COMMAND_QUERY_FAILED;
                log.warn("ticket_prepared_reservation_command_query_failed reservationId={}",
                        reservation.getReservationId());
                return;
            }
            OrderCommandStatusRespDTO command = result.getData();
            if (ORDER_COMMAND_SUCCEEDED.equals(command.getStatus())) {
                if (Integer.valueOf(RESERVATION_RELEASING).equals(reservation.getReservationStatus())) {
                    // 失败释放已领取后再观察到成功命令属于跨服务数据矛盾，不能自行猜测哪边为准。
                    outcome = RecoveryOutcome.TERMINAL_CONFLICT;
                    log.error("ticket_prepared_reservation_terminal_conflict reservationId={}",
                            reservation.getReservationId());
                    return;
                }
                // 订单已成功创建但同步响应或本地绑定中断时，使用行锁方法幂等补回订单关联。
                ticketSeatReservationReleaseService.bindOrder(reservation.getReservationId());
                outcome = RecoveryOutcome.COMMAND_SUCCEEDED;
                return;
            }
            if (ORDER_COMMAND_FAILED.equals(command.getStatus())) {
                // FAILED 由订单服务独立事务持久化，代表本次稳定命令未创建订单，可以安全释放库存。
                ticketSeatReservationReleaseService.releasePreparedReservation(reservation.getReservationId());
                outcome = RecoveryOutcome.COMMAND_FAILED;
                return;
            }
            // PROCESSING 和 NOT_FOUND 都不能证明原请求没有继续执行，必须保留 PREPARED 资源。
            outcome = RecoveryOutcome.COMMAND_PENDING;
            log.info("ticket_prepared_reservation_pending reservationId={}, commandStatus={}",
                    reservation.getReservationId(), command.getStatus());
        } catch (RuntimeException ex) {
            // 远程查询、绑定或释放异常均保留当前状态，由下一轮以同一 reservationId 幂等重试。
            log.error("ticket_prepared_reservation_recovery_failed reservationId={}",
                    reservation.getReservationId(), ex);
        } finally {
            // 后台扫描线程必须恢复原有上下文，避免后续任务意外沿用前一位用户身份。
            restoreUser(previousUser);
            // 指标不携带任何业务主键，完整关联关系仍由当前结构化日志保存。
            recordRecoverySafely(RecoveryFlow.PREPARED_COMMAND, outcome, startedNanos);
        }
    }

    /**
     * 记录恢复指标并隔离指标注册表故障，保证监控不可用时恢复链路仍能继续。
     *
     * @param flow 当前恢复链路类型
     * @param outcome 当前恢复结果
     * @param startedNanos 本次恢复开始的单调时钟值
     */
    private void recordRecoverySafely(RecoveryFlow flow, RecoveryOutcome outcome, long startedNanos) {
        try {
            // Micrometer 只用于观测，不能反向改变 reservation 的绑定、释放或重试语义。
            ticketSeatReservationRecoveryMetrics.recordRecovery(flow, outcome, startedNanos);
        } catch (RuntimeException metricsException) {
            // 仅输出异常类型，避免监控侧异常正文淹没业务恢复日志。
            log.warn("ticket_reservation_recovery_metrics_record_failed flow={}, outcome={}, exceptionType={}",
                    flow, outcome, metricsException.getClass().getSimpleName());
        }
    }

    /**
     * 复制当前线程用户上下文，供后台命令查询结束后恢复。
     *
     * @return 当前线程没有用户时返回 null
     */
    private UserInfoDTO currentUser() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            return null;
        }
        // 仅复制当前已经存在的身份字段，不构造令牌或额外权限。
        return UserInfoDTO.builder()
                .userId(userId)
                .username(UserContext.getUsername())
                .realName(UserContext.getRealName())
                .token(UserContext.getToken())
                .build();
    }

    /**
     * 恢复扫描前用户上下文或清理后台线程临时身份。
     *
     * @param previousUser 扫描前捕获的用户上下文
     */
    private void restoreUser(UserInfoDTO previousUser) {
        // HTTP 手动触发和定时任务共用恢复逻辑，不能把临时身份泄漏到后续执行。
        if (previousUser == null) {
            UserContext.removeUser();
        } else {
            UserContext.setUser(previousUser);
        }
    }
}
