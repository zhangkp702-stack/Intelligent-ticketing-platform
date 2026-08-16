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
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketSeatReservationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketSeatReservationMapper;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapReleaseResult;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 reservation 状态机在 Redis owner 已变更时不会再次操作新用户的位图。
 */
class TicketSeatReservationReleaseServiceTests {

    /**
     * 远程下单前必须保存不含订单号的 PREPARED 快照和稳定命令。
     */
    @Test
    void preparesReservationBeforeOrderCreation() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        when(reservationMapper.insert(any())).thenReturn(1);
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, mock(SeatService.class), mock(RedisSeatBitmapService.class),
                mock(TicketAvailabilityTokenBucket.class), mock(TransactionTemplate.class));
        TrainPurchaseTicketRespDTO ticket = ticket();

        // PREPARED 记录只绑定稳定命令和座位 owner，订单号等待远程成功后再写入。
        service.prepareReservation("reservation-a", "purchase-a", "purchase-a:create-order",
                "1001", "alice", 1L, "A", "B", new Date(1L), new Date(1L), List.of(ticket));

        ArgumentCaptor<TicketSeatReservationDO> captor = ArgumentCaptor.forClass(TicketSeatReservationDO.class);
        verify(reservationMapper).insert(captor.capture());
        TicketSeatReservationDO prepared = captor.getValue();
        assertEquals("reservation-a", prepared.getReservationId());
        assertEquals("purchase-a:create-order", prepared.getCommandId());
        assertEquals("1001", prepared.getUserId());
        assertEquals(0, prepared.getReservationStatus());
        assertNull(prepared.getOrderSn());
    }

    /**
     * 远程订单成功后必须在独立本地事务内把 PREPARED 幂等推进为已绑定状态。
     */
    @Test
    void bindsPreparedReservationToOrder() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        TicketSeatReservationDO prepared = TicketSeatReservationDO.builder()
                .reservationId("reservation-a")
                .reservationStatus(0)
                .build();
        when(reservationMapper.selectByReservationIdForUpdate("reservation-a")).thenReturn(prepared);
        when(reservationMapper.updateById(any())).thenReturn(1);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, mock(SeatService.class), mock(RedisSeatBitmapService.class),
                mock(TicketAvailabilityTokenBucket.class), transactionTemplate);

        // 绑定同时写入订单号和生命周期，避免关闭扫描读取到半完成关系。
        service.bindOrder("reservation-a", "order-1");

        assertEquals("order-1", prepared.getOrderSn());
        assertEquals(1, prepared.getReservationStatus());
        verify(reservationMapper).updateById(prepared);
    }

    /**
     * 订单命令明确失败后，PREPARED reservation 必须先领取释放权再完成三类资源释放。
     */
    @Test
    void releasesPreparedReservationOnlyAfterClaimingFailureRelease() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        SeatService seatService = mock(SeatService.class);
        RedisSeatBitmapService redisSeatBitmapService = mock(RedisSeatBitmapService.class);
        TicketAvailabilityTokenBucket tokenBucket = mock(TicketAvailabilityTokenBucket.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        TicketSeatReservationDO reservation = reservation();
        reservation.setOrderSn(null);
        reservation.setReservationStatus(0);
        when(reservationMapper.selectByReservationIdForUpdate("reservation-a")).thenReturn(reservation);
        when(reservationMapper.updateById(any())).thenReturn(1);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(redisSeatBitmapService.releaseByReservationId(eq("1"), any(Date.class), eq("A"), eq("B"), anyList(), eq("reservation-a")))
                .thenReturn(RedisSeatBitmapReleaseResult.RELEASED);
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, seatService, redisSeatBitmapService, tokenBucket, transactionTemplate);

        service.releasePreparedReservation("reservation-a");

        // 终态只有在数据库、Redis 位图和令牌桶均完成后才写入，避免扫描器过早停止恢复。
        assertEquals(3, reservation.getReservationStatus());
        assertEquals(1, reservation.getDbSeatReleaseStatus());
        assertEquals(1, reservation.getRedisBitmapReleaseStatus());
        assertEquals(1, reservation.getTokenRollbackStatus());
        verify(seatService).unlock(eq("1"), any(Date.class), eq("A"), eq("B"), anyList());
    }

    /**
     * 失败释放已经领取后，迟到订单成功不能再覆盖为 BOUND，必须交由对账告警处理。
     */
    @Test
    void rejectsOrderBindingAfterFailureReleaseHasBeenClaimed() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        TicketSeatReservationDO reservation = TicketSeatReservationDO.builder()
                .reservationId("reservation-a")
                .reservationStatus(2)
                .build();
        when(reservationMapper.selectByReservationIdForUpdate("reservation-a")).thenReturn(reservation);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, mock(SeatService.class), mock(RedisSeatBitmapService.class),
                mock(TicketAvailabilityTokenBucket.class), transactionTemplate);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.bindOrder("reservation-a", "order-1"));

        // RELEASING 不是合法绑定来源，避免失败释放与迟到成功并发时重新占用已回收库存。
        assertEquals("座位占用记录状态不允许绑定订单", exception.getMessage());
    }

    /**
     * 数据库座位释放完成后，Redis owner 已由新 reservation 接管时只记录冲突并继续幂等回滚旧 reservation 的令牌。
     */
    @Test
    void recordsOwnerChangedWithoutRetryingTheDatabaseSeatRelease() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        SeatService seatService = mock(SeatService.class);
        RedisSeatBitmapService redisSeatBitmapService = mock(RedisSeatBitmapService.class);
        TicketAvailabilityTokenBucket tokenBucket = mock(TicketAvailabilityTokenBucket.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        TicketSeatReservationDO reservation = reservation();

        when(reservationMapper.selectByOrderSn("order-1")).thenReturn(List.of(reservation));
        when(reservationMapper.selectByReservationIdForUpdate("reservation-a")).thenReturn(reservation);
        when(reservationMapper.updateById(any())).thenReturn(1);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(redisSeatBitmapService.releaseByReservationId(eq("1"), any(Date.class), eq("A"), eq("B"), anyList(), eq("reservation-a")))
                .thenReturn(RedisSeatBitmapReleaseResult.OWNER_CHANGED);
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, seatService, redisSeatBitmapService, tokenBucket, transactionTemplate);

        service.releaseOrder("order-1");

        assertEquals(1, reservation.getDbSeatReleaseStatus());
        assertEquals(2, reservation.getRedisBitmapReleaseStatus());
        assertEquals(1, reservation.getTokenRollbackStatus());
        verify(seatService).unlock(eq("1"), any(Date.class), eq("A"), eq("B"), anyList());
        verify(redisSeatBitmapService).releaseByReservationId(eq("1"), any(Date.class), eq("A"), eq("B"), anyList(), eq("reservation-a"));
        verify(tokenBucket).rollbackInBucketIfNecessary(any(TicketOrderDetailRespDTO.class),
                any(Date.class), eq("reservation-a"), anyBoolean());
    }

    /**
     * 构造包含一个座位区间的待释放 reservation。
     *
     * @return 测试使用的 reservation 记录
     */
    private TicketSeatReservationDO reservation() {
        TrainPurchaseTicketRespDTO ticket = ticket();
        return TicketSeatReservationDO.builder()
                .id(1L)
                .reservationId("reservation-a")
                .orderSn("order-1")
                .reservationStatus(1)
                .trainId(1L)
                .serviceDate(new Date())
                .departure("A")
                .arrival("B")
                .seatPayload(JSON.toJSONString(List.of(ticket)))
                .dbSeatReleaseStatus(0)
                .redisBitmapReleaseStatus(0)
                .tokenRollbackStatus(0)
                .build();
    }

    /**
     * 构造一个固定座位明细，供 PREPARED 和释放测试复用。
     *
     * @return 测试座位
     */
    private TrainPurchaseTicketRespDTO ticket() {
        // 座位快照只填充释放和序列化所需字段。
        TrainPurchaseTicketRespDTO ticket = new TrainPurchaseTicketRespDTO();
        ticket.setSeatType(1);
        ticket.setCarriageNumber("01");
        ticket.setSeatNumber("1A");
        return ticket;
    }
}
