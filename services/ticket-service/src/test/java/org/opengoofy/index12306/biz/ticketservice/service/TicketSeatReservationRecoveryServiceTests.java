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

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketSeatReservationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketSeatReservationMapper;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.OrderCommandStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketSeatReservationRecoveryMetrics;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 reservation 扫描在关闭订单、建单未知和失败重试场景下的恢复边界。
 */
class TicketSeatReservationRecoveryServiceTests {

    /**
     * 清理恢复器为 Feign 查询临时设置的用户上下文，避免测试之间共享线程身份。
     */
    @org.junit.jupiter.api.AfterEach
    void clearUserContext() {
        // UserContext 使用 ThreadLocal，必须在每个用例结束后主动清理。
        UserContext.removeUser();
    }

    /**
     * Canal 事件丢失时，已关闭订单的未完成 reservation 必须重新进入可靠回滚链路。
     */
    @Test
    void replaysReliableRollbackForClosedOrderWithIncompleteReservation() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        TicketSeatReservationDO reservation = reservation();
        when(reservationMapper.selectStaleIncompleteReservations(any(Date.class), eq(10)))
                .thenReturn(List.of(reservation));
        when(ticketOrderRemoteService.queryTicketOrderByOrderSn("order-1"))
                .thenReturn(Results.success(orderDetail(30)));
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);

        service.recoverStaleClosedReservations();

        verify(orderCloseRollbackService).rollback("order-1");
    }

    /**
     * 订单仍处于待支付状态时，扫描器不得依据本地超时提前释放真实库存。
     */
    @Test
    void keepsReservationWhenOrderIsNotClosed() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        when(reservationMapper.selectStaleIncompleteReservations(any(Date.class), eq(10)))
                .thenReturn(List.of(reservation()));
        when(ticketOrderRemoteService.queryTicketOrderByOrderSn("order-1"))
                .thenReturn(Results.success(orderDetail(0)));
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);

        service.recoverStaleClosedReservations();

        verify(orderCloseRollbackService, never()).rollback("order-1");
    }

    /**
     * 显式开启测试清理时，仅将缺少 Outbox 的超时 loadtest 预占记录交给正式释放器回滚。
     */
    @Test
    void releasesStaleLoadTestPreparedReservationWithoutOutboxWhenExplicitlyEnabled() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        TicketSeatReservationDO reservation = preparedReservation();
        reservation.setUsername("loadtest0001");
        when(reservationMapper.selectStaleLoadTestPreparedReservationsWithoutOutbox(any(Date.class), eq(10)))
                .thenReturn(List.of(reservation));
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);
        ReflectionTestUtils.setField(service, "loadTestOrphanReleaseEnabled", true);

        service.recoverStaleLoadTestPreparedReservationsWithoutOutbox();

        // 测试孤儿必须复用正式释放器，禁止测试代码直接修改数据库、Redis 或令牌桶状态。
        verify(ticketSeatReservationReleaseService).releasePreparedReservation("reservation-prepared");
        verify(ticketOrderRemoteService, never()).queryCommandStatus(any());
    }

    /**
     * 订单服务已成功但同步响应或本地绑定中断时，恢复器必须使用原用户身份幂等补绑订单号。
     */
    @Test
    void bindsPreparedReservationAfterOrderCommandSucceeded() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        TicketSeatReservationDO reservation = preparedReservation();
        when(reservationMapper.selectStalePreparedReservations(any(Date.class), eq(10)))
                .thenReturn(List.of(reservation));
        when(ticketOrderRemoteService.queryCommandStatus("purchase-a:create-order"))
                .thenReturn(Results.success(commandStatus("SUCCEEDED", "order-1")));
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);

        service.recoverStalePreparedReservations();

        // 成功命令只补绑订单，不能再次创建订单或提前释放库存。
        verify(ticketSeatReservationReleaseService).bindOrder("reservation-prepared", "order-1");
        verify(ticketSeatReservationReleaseService, never()).releasePreparedReservation(any());
    }

    /**
     * 订单服务独立持久化 FAILED 后，恢复器才能安全释放对应 PREPARED 座位。
     */
    @Test
    void releasesPreparedReservationAfterOrderCommandFailed() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        when(reservationMapper.selectStalePreparedReservations(any(Date.class), eq(10)))
                .thenReturn(List.of(preparedReservation()));
        when(ticketOrderRemoteService.queryCommandStatus("purchase-a:create-order"))
                .thenReturn(Results.success(commandStatus("FAILED", null)));
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);

        service.recoverStalePreparedReservations();

        // 只有明确 FAILED 才进入释放服务，NOT_FOUND 和 PROCESSING 不属于可释放证据。
        verify(ticketSeatReservationReleaseService).releasePreparedReservation("reservation-prepared");
        verify(ticketSeatReservationReleaseService, never()).bindOrder(any(), any());
    }

    /**
     * 命令仍处理中或不存在时无法排除迟到请求，恢复器必须保留 PREPARED 座位。
     */
    @Test
    void keepsPreparedReservationWhenOrderCommandIsNotTerminal() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        when(reservationMapper.selectStalePreparedReservations(any(Date.class), eq(10)))
                .thenReturn(List.of(preparedReservation()));
        when(ticketOrderRemoteService.queryCommandStatus("purchase-a:create-order"))
                .thenReturn(Results.success(commandStatus("PROCESSING", null)));
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);

        service.recoverStalePreparedReservations();

        // 未终态命令只能等待下一轮权威查询，不能猜测失败并释放真实库存。
        verify(ticketSeatReservationReleaseService, never()).releasePreparedReservation(any());
        verify(ticketSeatReservationReleaseService, never()).bindOrder(any(), any());
    }

    /**
     * 远程命令查询失败时不能把网络异常当作失败终态，必须保留 PREPARED 记录等待下轮查询。
     */
    @Test
    void keepsPreparedReservationWhenCommandQueryThrows() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        when(reservationMapper.selectStalePreparedReservations(any(Date.class), eq(10)))
                .thenReturn(List.of(preparedReservation()));
        when(ticketOrderRemoteService.queryCommandStatus("purchase-a:create-order"))
                .thenThrow(new ServiceException("order service timeout"));
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);

        service.recoverStalePreparedReservations();

        // 查询超时不产生任何释放或绑定副作用，下一轮仍可通过相同 commandId 对账。
        verify(ticketSeatReservationReleaseService, never()).releasePreparedReservation(any());
        verify(ticketSeatReservationReleaseService, never()).bindOrder(any(), any());
    }

    /**
     * 远程服务未找到命令同样不能排除迟到请求，恢复器必须把它视为未知而非失败。
     */
    @Test
    void keepsPreparedReservationWhenOrderCommandIsNotFound() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        when(reservationMapper.selectStalePreparedReservations(any(Date.class), eq(10)))
                .thenReturn(List.of(preparedReservation()));
        when(ticketOrderRemoteService.queryCommandStatus("purchase-a:create-order"))
                .thenReturn(Results.success(commandStatus("NOT_FOUND", null)));
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);

        service.recoverStalePreparedReservations();

        // NOT_FOUND 不是订单服务持久化的 FAILED 终态，不能据此释放真实库存。
        verify(ticketSeatReservationReleaseService, never()).releasePreparedReservation(any());
        verify(ticketSeatReservationReleaseService, never()).bindOrder(any(), any());
    }

    /**
     * 进程在远程订单成功后、本地绑定完成前中断时，重复扫描必须使用相同订单号重试绑定。
     */
    @Test
    void retriesOrderBindingAfterFirstAttemptFails() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        TicketSeatReservationDO reservation = preparedReservation();
        when(reservationMapper.selectStalePreparedReservations(any(Date.class), eq(10)))
                .thenReturn(List.of(reservation));
        when(ticketOrderRemoteService.queryCommandStatus("purchase-a:create-order"))
                .thenReturn(Results.success(commandStatus("SUCCEEDED", "order-1")));
        doThrow(new ServiceException("bind interrupted"))
                .doNothing()
                .when(ticketSeatReservationReleaseService)
                .bindOrder("reservation-prepared", "order-1");
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);

        service.recoverStalePreparedReservations();
        service.recoverStalePreparedReservations();

        // 首次绑定异常不改变 PREPARED，第二轮仍以同一命令返回的订单号重试。
        verify(ticketSeatReservationReleaseService, times(2)).bindOrder("reservation-prepared", "order-1");
        verify(ticketSeatReservationReleaseService, never()).releasePreparedReservation(any());
    }

    /**
     * FAILED 后释放中断时，后续扫描必须重复调用同一 reservation 的幂等释放器。
     */
    @Test
    void retriesPreparedReleaseAfterFirstAttemptFails() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        TicketSeatReservationDO reservation = preparedReservation();
        reservation.setReservationStatus(2);
        when(reservationMapper.selectStalePreparedReservations(any(Date.class), eq(10)))
                .thenReturn(List.of(reservation));
        when(ticketOrderRemoteService.queryCommandStatus("purchase-a:create-order"))
                .thenReturn(Results.success(commandStatus("FAILED", null)));
        doThrow(new ServiceException("redis release interrupted"))
                .doNothing()
                .when(ticketSeatReservationReleaseService)
                .releasePreparedReservation("reservation-prepared");
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);

        service.recoverStalePreparedReservations();
        service.recoverStalePreparedReservations();

        // RELEASING 状态的重试不会重新绑定订单，只会继续已领取的失败释放。
        verify(ticketSeatReservationReleaseService, times(2)).releasePreparedReservation("reservation-prepared");
        verify(ticketSeatReservationReleaseService, never()).bindOrder(any(), any());
    }

    /**
     * 已领取失败释放时却观察到迟到成功命令属于数据矛盾，恢复器必须停止自动修改资源。
     */
    @Test
    void keepsReleasingReservationWhenLateSuccessConflictsWithFailureRelease() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketOrderRemoteService ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        OrderCloseRollbackService orderCloseRollbackService = mock(OrderCloseRollbackService.class);
        TicketSeatReservationReleaseService ticketSeatReservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        TicketSeatReservationDO reservation = preparedReservation();
        reservation.setReservationStatus(2);
        UserContext.setUser(UserInfoDTO.builder().userId("9000").username("caller").build());
        when(reservationMapper.selectStalePreparedReservations(any(Date.class), eq(10)))
                .thenReturn(List.of(reservation));
        when(ticketOrderRemoteService.queryCommandStatus("purchase-a:create-order"))
                .thenAnswer(invocation -> {
                    // 查询阶段必须临时恢复 reservation 所属用户，不能沿用触发扫描的调用者身份。
                    assertThat(UserContext.getUserId()).isEqualTo("1001");
                    return Results.success(commandStatus("SUCCEEDED", "order-1"));
                });
        TicketSeatReservationRecoveryService service = service(reservationMapper, ticketOrderRemoteService,
                orderCloseRollbackService, ticketSeatReservationReleaseService);

        service.recoverStalePreparedReservations();

        // 矛盾终态由人工处理，自动恢复既不绑定也不继续释放。
        verify(ticketSeatReservationReleaseService, never()).bindOrder(any(), any());
        verify(ticketSeatReservationReleaseService, never()).releasePreparedReservation(any());
        assertThat(UserContext.getUserId()).isEqualTo("9000");
    }

    /**
     * 构造恢复服务并注入小批次配置，保证 mapper 参数可断言且不依赖 Spring 容器。
     *
     * @param reservationMapper reservation 持久层 mock
     * @param ticketOrderRemoteService 订单服务 mock
     * @param orderCloseRollbackService 关闭回滚服务 mock
     * @return 可直接触发扫描的恢复服务
     */
    private TicketSeatReservationRecoveryService service(TicketSeatReservationMapper reservationMapper,
                                                          TicketOrderRemoteService ticketOrderRemoteService,
                                                          OrderCloseRollbackService orderCloseRollbackService,
                                                          TicketSeatReservationReleaseService ticketSeatReservationReleaseService) {
        TicketSeatReservationRecoveryService service = new TicketSeatReservationRecoveryService(reservationMapper,
                ticketOrderRemoteService, orderCloseRollbackService, ticketSeatReservationReleaseService,
                mock(TicketSeatReservationRecoveryMetrics.class));
        // 使用确定的配置值验证超时扫描的批次边界，不依赖测试环境变量。
        ReflectionTestUtils.setField(service, "reservationRecoveryTimeoutMillis", 60_000L);
        ReflectionTestUtils.setField(service, "reservationRecoveryBatchSize", 10);
        return service;
    }

    /**
     * 构造仍有 Redis 释放步骤待处理的座位占用记录。
     *
     * @return 测试用超时 reservation
     */
    private TicketSeatReservationDO reservation() {
        return TicketSeatReservationDO.builder()
                .reservationId("reservation-a")
                .orderSn("order-1")
                .dbSeatReleaseStatus(1)
                .redisBitmapReleaseStatus(0)
                .tokenRollbackStatus(1)
                .build();
    }

    /**
     * 构造包含用户和稳定命令的超时 PREPARED reservation。
     *
     * @return 供订单命令对账使用的座位占用记录
     */
    private TicketSeatReservationDO preparedReservation() {
        return TicketSeatReservationDO.builder()
                .reservationId("reservation-prepared")
                .commandId("purchase-a:create-order")
                .userId("1001")
                .username("alice")
                .reservationStatus(0)
                .dbSeatReleaseStatus(0)
                .redisBitmapReleaseStatus(0)
                .tokenRollbackStatus(0)
                .build();
    }

    /**
     * 构造订单服务返回的最小稳定命令终态。
     *
     * @param status 命令终态
     * @param orderSn 成功时的订单号
     * @return 仅包含恢复判断字段的远程响应
     */
    private OrderCommandStatusRespDTO commandStatus(String status, String orderSn) {
        OrderCommandStatusRespDTO result = new OrderCommandStatusRespDTO();
        // 恢复器只依赖命令状态和成功订单号，避免测试耦合订单详情字段。
        result.setStatus(status);
        result.setOrderSn(orderSn);
        return result;
    }

    /**
     * 构造订单服务返回的最小权威状态。
     *
     * @param status 订单状态码
     * @return 订单详情
     */
    private TicketOrderDetailRespDTO orderDetail(int status) {
        TicketOrderDetailRespDTO detail = new TicketOrderDetailRespDTO();
        // 只设置恢复判断所需的状态，避免测试耦合订单展示字段。
        detail.setStatus(status);
        return detail;
    }
}
