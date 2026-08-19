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

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketOrderCreationMetrics;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketOrderCreationMetrics.DispatchOutcome;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxRecord;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.opengoofy.index12306.biz.ticketservice.service.TicketSeatReservationReleaseService.ORDER_CREATION_EVENT_NAMESPACE;
import static org.opengoofy.index12306.biz.ticketservice.service.TicketSeatReservationReleaseService.ORDER_CREATION_FAILED;
import static org.opengoofy.index12306.biz.ticketservice.service.TicketSeatReservationReleaseService.ORDER_CREATION_SUCCEEDED;

/**
 * 从本地 Outbox 可恢复地派发订单创建请求。
 */
@Slf4j
@Service
public class TicketOrderCreationDispatcher {

    private final ReliableEventStore reliableEventStore;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final TicketSeatReservationReleaseService reservationReleaseService;
    private final TicketOrderCreationMetrics ticketOrderCreationMetrics;
    private final Executor executor;
    private final String workerId = "ticket-order-creation-" + UUID.randomUUID();

    @Value("${index12306.ticket.async-order.batch-size:32}")
    private int batchSize;

    @Value("${index12306.ticket.async-order.lease-millis:30000}")
    private long leaseMillis;

    @Value("${index12306.ticket.async-order.retry-base-millis:200}")
    private long retryBaseMillis;

    @Value("${index12306.ticket.async-order.retry-max-millis:5000}")
    private long retryMaxMillis;

    /**
     * 创建异步建单派发器。
     *
     * @param reliableEventStore 本地可靠事件存储
     * @param ticketOrderRemoteService 订单服务客户端
     * @param reservationReleaseService 座位占用绑定与状态服务
     * @param ticketOrderCreationMetrics 异步建单指标记录器
     * @param executor 异步建单专用执行器
     */
    public TicketOrderCreationDispatcher(
            ReliableEventStore reliableEventStore,
            TicketOrderRemoteService ticketOrderRemoteService,
            TicketSeatReservationReleaseService reservationReleaseService,
            TicketOrderCreationMetrics ticketOrderCreationMetrics,
            @Qualifier("ticketOrderCreationExecutor") Executor executor) {
        this.reliableEventStore = reliableEventStore;
        this.ticketOrderRemoteService = ticketOrderRemoteService;
        this.reservationReleaseService = reservationReleaseService;
        this.ticketOrderCreationMetrics = ticketOrderCreationMetrics;
        this.executor = executor;
    }

    /**
     * 回收过期租约并并发派发一批待创建订单。
     */
    @Scheduled(fixedDelayString = "${index12306.ticket.async-order.interval-millis:200}")
    public void dispatchPendingOrders() {
        try {
            Instant now = Instant.now();
            int normalizedBatchSize = Math.max(1, batchSize);
            // 先回收宕机实例遗留的过期租约，再用围栏令牌认领当前批次。
            int recoveredCount = reliableEventStore.recoverExpiredPublications(
                    ORDER_CREATION_EVENT_NAMESPACE, now, normalizedBatchSize);
            ticketOrderCreationMetrics.recordLeaseRecovered(recoveredCount);
            List<ReliableOutboxRecord> claimed = reliableEventStore.claimPublishable(
                    ORDER_CREATION_EVENT_NAMESPACE,
                    workerId,
                    now,
                    now.plusMillis(Math.max(1000L, leaseMillis)),
                    normalizedBatchSize);
            if (claimed.isEmpty()) {
                return;
            }
            ticketOrderCreationMetrics.recordClaimed(claimed.size());
            // 同一批次并发调用订单服务，不持有票务数据库事务或 HTTP 请求线程。
            List<CompletableFuture<?>> futures = new ArrayList<>(claimed.size());
            for (ReliableOutboxRecord event : claimed) {
                try {
                    futures.add(CompletableFuture.runAsync(() -> dispatchOne(event), executor));
                } catch (RejectedExecutionException ex) {
                    // 任务尚未执行时立即交还当前租约，避免无意义地等待完整租约超时。
                    rescheduleRejectedEvent(event, ex);
                }
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (RuntimeException ex) {
            // 调度周期失败时下轮仍可继续，指标与日志用于区分单事件失败和存储层故障。
            ticketOrderCreationMetrics.recordDispatchCycleFailure();
            log.error("ticket_async_order_dispatch_cycle_failed exceptionType={}", ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * 使用稳定 commandId 创建订单，并在本地绑定成功后提交 Outbox 围栏。
     *
     * @param event 已取得当前实例租约的建单事件
     */
    private void dispatchOne(ReliableOutboxRecord event) {
        ticketOrderCreationMetrics.recordDispatchWait(event.createdAt());
        UserInfoDTO previousUser = currentUser();
        try {
            // 载荷是锁座事务中持久化的不可变建单请求，重试不再查询乘车人或票价。
            TicketOrderCreateRemoteReqDTO request = JSON.parseObject(
                    event.payload(), TicketOrderCreateRemoteReqDTO.class);
            if (request == null || request.getUserId() == null || request.getUserId().isBlank()) {
                throw new ServiceException("异步建单事件缺少用户信息");
            }
            UserContext.setUser(UserInfoDTO.builder()
                    .userId(request.getUserId())
                    .username(request.getUsername())
                    .build());

            // 先识别已由对账链路收敛的终态，避免对已绑定或已释放记录继续远程重试。
            TicketPurchaseStatusRespDTO current = reservationReleaseService
                    .queryPurchaseStatus(event.aggregateId());
            if (ORDER_CREATION_SUCCEEDED.equals(current.getStatus())) {
                recordPublishResult(event, current.getOrderSn(), DispatchOutcome.RECONCILED);
                return;
            }
            if (ORDER_CREATION_FAILED.equals(current.getStatus())) {
                recordPublishResult(event, event.aggregateId(), DispatchOutcome.RECONCILED);
                return;
            }

            // 订单服务以 commandId 幂等执行，可安全覆盖远程成功但本地响应丢失的窗口。
            Result<String> result = ticketOrderRemoteService.createTicketOrder(request);
            if (!result.isSuccess() || result.getData() == null || result.getData().isBlank()) {
                throw new ServiceException("订单服务未返回有效订单号");
            }
            // 先幂等绑定真实订单，再标记 Outbox 完成；中间宕机会重放而不会丢单。
            reservationReleaseService.bindOrder(event.aggregateId(), result.getData());
            recordPublishResult(event, result.getData(), DispatchOutcome.PUBLISHED);
            log.info("ticket_async_order_created reservationId={}, orderSn={}, attempt={}",
                    event.aggregateId(), result.getData(), event.publishAttemptCount());
        } catch (RuntimeException ex) {
            // 失败不释放座位；未知结果必须使用同一 commandId 重试或由命令对账收敛。
            long retryDelay = retryDelayMillis(event.publishAttemptCount());
            boolean rescheduled = reliableEventStore.markPublishFailed(
                    event.key(),
                    requireLease(event),
                    event.eventVersion(),
                    "ORDER_CREATE_RETRY",
                    ex.getClass().getSimpleName() + ": " + safeMessage(ex),
                    Instant.now().plusMillis(retryDelay),
                    Instant.now());
            log.warn("ticket_async_order_retry reservationId={}, attempt={}, retryDelayMillis={}, rescheduled={}, exceptionType={}",
                    event.aggregateId(), event.publishAttemptCount(), retryDelay, rescheduled,
                    ex.getClass().getSimpleName());
            ticketOrderCreationMetrics.recordDispatchOutcome(DispatchOutcome.RETRY);
        } finally {
            // 后台线程会复用，每条事件结束时必须恢复或清理用户上下文。
            restoreUser(previousUser);
        }
    }

    /**
     * 用当前租约围栏完成建单事件，并记录成功或围栏丢失结果。
     *
     * @param event 已认领事件
     * @param orderReference 真实订单号或失败终态的 reservationId
     * @param successOutcome 围栏提交成功时应记录的固定结果
     */
    private void recordPublishResult(ReliableOutboxRecord event, String orderReference, DispatchOutcome successOutcome) {
        // 围栏更新失败代表租约已被新实例接管，不能覆盖新实例的处理结果。
        boolean published = reliableEventStore.markPublished(
                event.key(), requireLease(event), event.eventVersion(), orderReference, Instant.now());
        if (!published) {
            log.warn("ticket_async_order_publish_fence_lost reservationId={}, attempt={}",
                    event.aggregateId(), event.publishAttemptCount());
        }
        ticketOrderCreationMetrics.recordDispatchOutcome(published ? successOutcome : DispatchOutcome.FENCE_LOST);
    }

    /**
     * 将未被线程池接收的事件立即退回 Outbox，并保留当前失败分类供人工核对。
     *
     * @param event 已取得当前实例租约但尚未执行的事件
     * @param exception 执行器拒绝异常
     */
    private void rescheduleRejectedEvent(ReliableOutboxRecord event, RejectedExecutionException exception) {
        long retryDelay = retryDelayMillis(event.publishAttemptCount());
        boolean rescheduled = reliableEventStore.markPublishFailed(
                event.key(),
                requireLease(event),
                event.eventVersion(),
                "ORDER_CREATE_EXECUTOR_REJECTED",
                safeMessage(exception),
                Instant.now().plusMillis(retryDelay),
                Instant.now());
        // 拒绝事件与远程调用失败分开统计，便于区分容量不足和下游服务异常。
        ticketOrderCreationMetrics.recordDispatchOutcome(DispatchOutcome.EXECUTOR_REJECTED);
        log.warn("ticket_async_order_executor_rejected reservationId={}, attempt={}, retryDelayMillis={}, rescheduled={}",
                event.aggregateId(), event.publishAttemptCount(), retryDelay, rescheduled);
    }

    /**
     * 计算有上限的指数退避时间。
     *
     * @param attemptCount 已认领次数
     * @return 下次派发前的等待毫秒数
     */
    private long retryDelayMillis(int attemptCount) {
        long base = Math.max(1L, retryBaseMillis);
        long maximum = Math.max(base, retryMaxMillis);
        int exponent = Math.min(Math.max(0, attemptCount - 1), 10);
        return Math.min(maximum, base * (1L << exponent));
    }

    /**
     * 获取已认领事件的完整租约。
     *
     * @param event 已认领事件
     * @return 可用于围栏提交的租约
     */
    private ReliableEventLease requireLease(ReliableOutboxRecord event) {
        if (event.lease() == null) {
            throw new IllegalStateException("异步建单事件缺少发布租约");
        }
        return event.lease();
    }

    /**
     * 生成不包含业务载荷的限长错误摘要。
     *
     * @param throwable 当前派发异常
     * @return 最多 400 个字符的安全摘要
     */
    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "no-message";
        }
        return message.length() <= 400 ? message : message.substring(0, 400);
    }

    /**
     * 复制当前线程用户上下文。
     *
     * @return 当前线程没有用户时返回空
     */
    private UserInfoDTO currentUser() {
        if (UserContext.getUserId() == null) {
            return null;
        }
        // 仅复制已存在字段，不为后台任务伪造额外权限。
        return UserInfoDTO.builder()
                .userId(UserContext.getUserId())
                .username(UserContext.getUsername())
                .realName(UserContext.getRealName())
                .token(UserContext.getToken())
                .build();
    }

    /**
     * 恢复调用前的用户上下文。
     *
     * @param previousUser 调用前捕获的用户
     */
    private void restoreUser(UserInfoDTO previousUser) {
        // 没有旧上下文时必须 remove，避免线程池泄露前一用户。
        if (previousUser == null) {
            UserContext.removeUser();
        } else {
            UserContext.setUser(previousUser);
        }
    }
}
