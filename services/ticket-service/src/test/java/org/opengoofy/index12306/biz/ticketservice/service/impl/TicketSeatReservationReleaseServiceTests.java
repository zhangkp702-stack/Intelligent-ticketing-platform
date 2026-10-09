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

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketCdcOutboxEventDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketSeatReservationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketCdcOutboxEventMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketSeatReservationMapper;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderItemCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapReleaseResult;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.inventory.TicketInventoryRollbackService;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 reservation 从初始化、建单到失败释放的持久化状态迁移。
 */
class TicketSeatReservationReleaseServiceTests {

    /**
     * MQ 建单命令发送前必须保存不含订单号的 PREPARED 快照和稳定命令。
     */
    @Test
    void preparesReservationBeforeOrderCreation() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketCdcOutboxEventMapper cdcOutboxEventMapper = mock(TicketCdcOutboxEventMapper.class);
        TicketSeatReservationDO initial = TicketSeatReservationDO.builder()
                .reservationId("reservation-a")
                .userId("1001")
                .trainId(1L)
                .seatCount(1)
                .reservationStatus(TicketSeatReservationReleaseService.RESERVATION_INIT)
                .build();
        when(reservationMapper.selectByReservationIdForUpdate("reservation-a")).thenReturn(initial);
        when(reservationMapper.updateById(any())).thenReturn(1);
        when(cdcOutboxEventMapper.insertIgnore(any())).thenReturn(1);
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, cdcOutboxEventMapper, mock(SeatService.class), mock(RedisSeatBitmapService.class),
                mock(TicketInventoryRollbackService.class), mock(TransactionTemplate.class));
        TrainPurchaseTicketRespDTO ticket = ticket();
        TicketOrderCreateRemoteReqDTO orderRequest = TicketOrderCreateRemoteReqDTO.builder()
                .userId("1001")
                .ticketOrderItems(List.of(TicketOrderItemCreateRemoteReqDTO.builder()
                        .carriageNumber("01")
                        .seatType(1)
                        .seatNumber("01A")
                        .realName("alice")
                        .idType(1)
                        .idCard("id-card")
                        .phone("13800000000")
                        .amount(100)
                        .ticketType(0)
                        .build()))
                .build();

        // PREPARED 和完整 MQ 建单事件必须由同一事务方法写入。
        service.prepareReservation("reservation-a", "1001", "alice", 1L, "A", "B",
                new Date(1L), new Date(1L), 1, List.of(ticket), orderRequest);

        ArgumentCaptor<TicketSeatReservationDO> captor = ArgumentCaptor.forClass(TicketSeatReservationDO.class);
        verify(reservationMapper).updateById(captor.capture());
        TicketSeatReservationDO prepared = captor.getValue();
        assertEquals("reservation-a", prepared.getReservationId());
        assertEquals("1001", prepared.getUserId());
        assertEquals(1, prepared.getSeatType());
        assertEquals(1, prepared.getSeatCount());
        assertEquals(0, prepared.getReservationStatus());
        assertNull(prepared.getOrderSn());
        ArgumentCaptor<TicketCdcOutboxEventDO> eventCaptor = ArgumentCaptor.forClass(TicketCdcOutboxEventDO.class);
        verify(cdcOutboxEventMapper).insertIgnore(eventCaptor.capture());
        assertEquals("reservation-a", eventCaptor.getValue().getAggregateId());
        assertEquals("reservation-a", eventCaptor.getValue().getDeduplicationKey());
        TicketOrderCreateCommandMessage message = JSON.parseObject(
                eventCaptor.getValue().getPayload(), TicketOrderCreateCommandMessage.class);
        assertEquals("reservation-a", message.getReservationId());
        assertEquals("1001", message.getRequest().getUserId());
    }

    /**
     * Redis 占位前必须在独立事务中保存不含具体座位的 INIT 记录。
     */
    @Test
    void createsInitialReservationBeforeRedisHold() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(reservationMapper.insert(any())).thenReturn(1);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, mock(TicketCdcOutboxEventMapper.class), mock(SeatService.class),
                mock(RedisSeatBitmapService.class), mock(TicketInventoryRollbackService.class), transactionTemplate);

        service.createInitialReservation(
                "reservation-a", "1001", "alice", 1L, "A", "B",
                new Date(1L), new Date(1L), 1, 1);

        ArgumentCaptor<TicketSeatReservationDO> captor = ArgumentCaptor.forClass(TicketSeatReservationDO.class);
        verify(reservationMapper).insert(captor.capture());
        TicketSeatReservationDO initial = captor.getValue();
        assertEquals(TicketSeatReservationReleaseService.RESERVATION_INIT, initial.getReservationStatus());
        assertEquals("[]", initial.getSeatPayload());
        assertEquals(1, initial.getDbSeatReleaseStatus());
        assertEquals(1, initial.getRedisBitmapReleaseStatus());
        assertEquals(0, initial.getTokenRollbackStatus());
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
                reservationMapper, mock(TicketCdcOutboxEventMapper.class), mock(SeatService.class),
                mock(RedisSeatBitmapService.class), mock(TicketInventoryRollbackService.class), transactionTemplate);

        // 订单号已在锁座前确定为 reservationId，绑定只推进生命周期。
        service.bindOrder("reservation-a");

        assertNull(prepared.getOrderSn());
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
        TicketInventoryRollbackService inventoryRollbackService = mock(TicketInventoryRollbackService.class);
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
        when(redisSeatBitmapService.releaseByReservationId(eq("1"), any(Date.class), eq("A"), eq("B"), eq(1), anyList(), eq("reservation-a")))
                .thenReturn(RedisSeatBitmapReleaseResult.RELEASED);
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, mock(TicketCdcOutboxEventMapper.class), seatService, redisSeatBitmapService,
                inventoryRollbackService, transactionTemplate);

        service.releasePreparedReservation("reservation-a");

        // 终态只有在数据库、Redis 位图和统一库存均完成后才写入，避免扫描器过早停止恢复。
        assertEquals(3, reservation.getReservationStatus());
        assertEquals(1, reservation.getDbSeatReleaseStatus());
        assertEquals(1, reservation.getRedisBitmapReleaseStatus());
        assertEquals(1, reservation.getTokenRollbackStatus());
        verify(seatService).unlock(eq("1"), any(Date.class), eq("A"), eq("B"), anyList());
    }

    /**
     * INIT 超时后只能回滚统一库存，不应访问尚未产生的数据库座位或 Redis 位图。
     */
    @Test
    void releasesInitialReservationWithoutSeatSnapshot() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        SeatService seatService = mock(SeatService.class);
        RedisSeatBitmapService redisSeatBitmapService = mock(RedisSeatBitmapService.class);
        TicketInventoryRollbackService inventoryRollbackService = mock(TicketInventoryRollbackService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        TicketSeatReservationDO initial = TicketSeatReservationDO.builder()
                .reservationId("reservation-a")
                .reservationStatus(TicketSeatReservationReleaseService.RESERVATION_INIT)
                .trainId(1L)
                .serviceDate(new Date(1L))
                .departure("A")
                .arrival("B")
                .seatType(1)
                .seatCount(1)
                .seatPayload("[]")
                .dbSeatReleaseStatus(1)
                .redisBitmapReleaseStatus(1)
                .tokenRollbackStatus(0)
                .build();
        when(reservationMapper.selectByReservationIdForUpdate("reservation-a")).thenReturn(initial);
        when(reservationMapper.updateById(any())).thenReturn(1);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, mock(TicketCdcOutboxEventMapper.class), seatService, redisSeatBitmapService,
                inventoryRollbackService, transactionTemplate);

        service.releaseUnpreparedReservation("reservation-a");

        assertEquals(TicketSeatReservationReleaseService.RESERVATION_ABORTED, initial.getReservationStatus());
        assertEquals(1, initial.getTokenRollbackStatus());
        verify(inventoryRollbackService).rollbackInitialIfNecessary(
                eq("1"), any(Date.class), eq("A"), eq("B"), eq(1), eq(1), eq("reservation-a"));
        verify(seatService, never()).unlock(anyString(), any(Date.class), anyString(), anyString(), anyList());
    }

    /**
     * 恢复任务已中止 INIT 时，迟到的库存扣减必须再执行一次幂等回滚。
     */
    @Test
    void rollsBackLateInventoryDeductionAfterInitialReservationWasAborted() {
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketInventoryRollbackService inventoryRollbackService = mock(TicketInventoryRollbackService.class);
        TicketSeatReservationDO aborted = TicketSeatReservationDO.builder()
                .reservationId("reservation-a")
                .reservationStatus(TicketSeatReservationReleaseService.RESERVATION_ABORTED)
                .trainId(1L)
                .serviceDate(new Date(1L))
                .departure("A")
                .arrival("B")
                .seatType(1)
                .seatCount(1)
                .build();
        when(reservationMapper.selectByReservationId("reservation-a")).thenReturn(aborted);
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, mock(TicketCdcOutboxEventMapper.class), mock(SeatService.class),
                mock(RedisSeatBitmapService.class), inventoryRollbackService, mock(TransactionTemplate.class));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.ensureInitialActiveAfterInventoryDeduct("reservation-a"));

        assertEquals("初始化座位预订已失效，请重新购票", exception.getMessage());
        verify(inventoryRollbackService).rollbackInitialIfNecessary(
                eq("1"), any(Date.class), eq("A"), eq("B"), eq(1), eq(1), eq("reservation-a"));
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
                reservationMapper, mock(TicketCdcOutboxEventMapper.class), mock(SeatService.class),
                mock(RedisSeatBitmapService.class), mock(TicketInventoryRollbackService.class), transactionTemplate);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.bindOrder("reservation-a"));

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
        TicketInventoryRollbackService inventoryRollbackService = mock(TicketInventoryRollbackService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        TicketSeatReservationDO reservation = reservation();

        when(reservationMapper.selectLegacyBoundByOrderSn("order-1")).thenReturn(List.of(reservation));
        when(reservationMapper.selectByReservationIdForUpdate("reservation-a")).thenReturn(reservation);
        when(reservationMapper.updateById(any())).thenReturn(1);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(redisSeatBitmapService.releaseByReservationId(eq("1"), any(Date.class), eq("A"), eq("B"), eq(1), anyList(), eq("reservation-a")))
                .thenReturn(RedisSeatBitmapReleaseResult.OWNER_CHANGED);
        TicketSeatReservationReleaseService service = new TicketSeatReservationReleaseService(
                reservationMapper, mock(TicketCdcOutboxEventMapper.class), seatService, redisSeatBitmapService,
                inventoryRollbackService, transactionTemplate);

        service.releaseOrder("order-1");

        assertEquals(1, reservation.getDbSeatReleaseStatus());
        assertEquals(2, reservation.getRedisBitmapReleaseStatus());
        assertEquals(1, reservation.getTokenRollbackStatus());
        verify(seatService).unlock(eq("1"), any(Date.class), eq("A"), eq("B"), anyList());
        verify(redisSeatBitmapService).releaseByReservationId(eq("1"), any(Date.class), eq("A"), eq("B"), eq(1), anyList(), eq("reservation-a"));
        verify(inventoryRollbackService).rollbackIfNecessary(eq("1"), any(Date.class), eq("A"), eq("B"),
                eq(1), eq(1), anyList(), eq("reservation-a"));
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
                .seatType(1)
                .seatCount(1)
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
