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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketOrderCreationMetrics;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxStatus;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证异步建单派发的成功绑定与失败重试边界。
 */
class TicketOrderCreationDispatcherTests {

    /**
     * 清理后台派发临时恢复的用户上下文。
     */
    @AfterEach
    void tearDown() {
        // 单元测试线程会复用，不允许用户信息泄露到其他用例。
        UserContext.removeUser();
    }

    /**
     * 远程建单成功时必须先绑定 reservation，再使用租约围栏完成 Outbox。
     */
    @Test
    void bindsOrderBeforeCompletingOutbox() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        TicketOrderRemoteService remoteService = mock(TicketOrderRemoteService.class);
        TicketSeatReservationReleaseService reservationService = mock(TicketSeatReservationReleaseService.class);
        ReliableOutboxRecord event = event(1);
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(100)))
                .thenReturn(List.of(event));
        when(reservationService.queryPurchaseStatus("reservation-a"))
                .thenReturn(status("PROCESSING", null));
        when(remoteService.createTicketOrder(any())).thenReturn(Results.success("order-1"));
        TicketOrderCreationDispatcher dispatcher = dispatcher(eventStore, remoteService, reservationService);

        // 一次扫描在当前线程同步执行，便于验证状态提交顺序。
        dispatcher.dispatchPendingOrders();

        verify(reservationService).bindOrder("reservation-a", "order-1");
        verify(eventStore).markPublished(eq(event.key()), eq(event.lease()), eq(1L), eq("order-1"), any());
        verify(eventStore, never()).markPublishFailed(any(), any(), eq(1L), anyString(), anyString(), any(), any());
    }

    /**
     * 远程结果未知时只安排同一事件重试，不绑定订单也不释放座位。
     */
    @Test
    void reschedulesUnknownRemoteResult() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        TicketOrderRemoteService remoteService = mock(TicketOrderRemoteService.class);
        TicketSeatReservationReleaseService reservationService = mock(TicketSeatReservationReleaseService.class);
        ReliableOutboxRecord event = event(2);
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(100)))
                .thenReturn(List.of(event));
        when(reservationService.queryPurchaseStatus("reservation-a"))
                .thenReturn(status("PROCESSING", null));
        when(remoteService.createTicketOrder(any())).thenThrow(new IllegalStateException("timeout"));
        TicketOrderCreationDispatcher dispatcher = dispatcher(eventStore, remoteService, reservationService);

        // 远程超时无法证明订单未创建，必须保留并重放稳定 commandId。
        dispatcher.dispatchPendingOrders();

        verify(eventStore).markPublishFailed(eq(event.key()), eq(event.lease()), eq(1L),
                eq("ORDER_CREATE_RETRY"), anyString(), any(), any());
        verify(reservationService, never()).bindOrder(anyString(), anyString());
        verify(eventStore, never()).markPublished(any(), any(), eq(1L), anyString(), any());
    }

    /**
     * 执行器饱和时必须立即退回当前事件，不能等发布租约自然超时后才恢复。
     */
    @Test
    void reschedulesEventImmediatelyWhenExecutorRejects() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        TicketOrderRemoteService remoteService = mock(TicketOrderRemoteService.class);
        TicketSeatReservationReleaseService reservationService = mock(TicketSeatReservationReleaseService.class);
        ReliableOutboxRecord event = event(1);
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(100)))
                .thenReturn(List.of(event));
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("saturated");
        };
        TicketOrderCreationDispatcher dispatcher = dispatcher(
                eventStore, remoteService, reservationService, rejectingExecutor);

        // 拒绝发生在任务开始前，必须以现有围栏立即安排重试。
        dispatcher.dispatchPendingOrders();

        verify(eventStore).markPublishFailed(eq(event.key()), eq(event.lease()), eq(1L),
                eq("ORDER_CREATE_EXECUTOR_REJECTED"), anyString(), any(), any());
        verify(remoteService, never()).createTicketOrder(any());
    }

    /**
     * 创建使用同步执行器的派发器。
     *
     * @param eventStore 可靠事件存储
     * @param remoteService 订单远程服务
     * @param reservationService reservation 状态服务
     * @return 已注入测试参数的派发器
     */
    private TicketOrderCreationDispatcher dispatcher(
            ReliableEventStore eventStore,
            TicketOrderRemoteService remoteService,
            TicketSeatReservationReleaseService reservationService) {
        return dispatcher(eventStore, remoteService, reservationService, Runnable::run);
    }

    /**
     * 创建可替换执行器的派发器。
     *
     * @param eventStore 可靠事件存储
     * @param remoteService 订单服务客户端
     * @param reservationService reservation 状态服务
     * @param executor 用于模拟同步执行或饱和拒绝的执行器
     * @return 已注入测试参数的派发器
     */
    private TicketOrderCreationDispatcher dispatcher(
            ReliableEventStore eventStore,
            TicketOrderRemoteService remoteService,
            TicketSeatReservationReleaseService reservationService,
            Executor executor) {
        TicketOrderCreationMetrics metrics = new TicketOrderCreationMetrics(
                new SimpleMeterRegistry(), eventStore);
        TicketOrderCreationDispatcher dispatcher = new TicketOrderCreationDispatcher(
                eventStore, remoteService, reservationService, metrics, executor);
        // 固定批次、租约和退避参数，避免单元测试依赖 Spring 配置注入。
        ReflectionTestUtils.setField(dispatcher, "batchSize", 100);
        ReflectionTestUtils.setField(dispatcher, "leaseMillis", 30000L);
        ReflectionTestUtils.setField(dispatcher, "retryBaseMillis", 200L);
        ReflectionTestUtils.setField(dispatcher, "retryMaxMillis", 5000L);
        return dispatcher;
    }

    /**
     * 创建已取得有效租约的建单 Outbox 记录。
     *
     * @param attemptCount 当前认领次数
     * @return 可直接派发的测试事件
     */
    private ReliableOutboxRecord event(int attemptCount) {
        TicketOrderCreateRemoteReqDTO request = TicketOrderCreateRemoteReqDTO.builder()
                .commandId("purchase-a:create-order")
                .userId("1001")
                .username("alice")
                .build();
        Instant now = Instant.now();
        // 事件携带 worker 和围栏令牌，用于验证迟到实例无法覆盖新结果。
        return new ReliableOutboxRecord(
                new ReliableEventKey("ticket-order-creation", "reservation-a"),
                "purchase-a:create-order",
                "CREATE_TICKET_ORDER",
                "reservation-a",
                JSON.toJSONString(request),
                1L,
                ReliableOutboxStatus.PUBLISHING,
                now,
                new ReliableEventLease("worker-a", 3L),
                now.plusSeconds(30),
                attemptCount,
                null,
                null,
                null,
                null,
                now,
                now);
    }

    /**
     * 创建 reservation 对外状态。
     *
     * @param status 建单状态
     * @param orderSn 可选订单号
     * @return 状态响应
     */
    private TicketPurchaseStatusRespDTO status(String status, String orderSn) {
        // 受理标识与当前 Outbox 聚合标识保持一致。
        return TicketPurchaseStatusRespDTO.builder()
                .reservationId("reservation-a")
                .status(status)
                .orderSn(orderSn)
                .build();
    }
}
