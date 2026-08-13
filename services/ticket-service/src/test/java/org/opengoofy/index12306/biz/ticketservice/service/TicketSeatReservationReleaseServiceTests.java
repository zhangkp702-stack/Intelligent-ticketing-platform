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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        TrainPurchaseTicketRespDTO ticket = new TrainPurchaseTicketRespDTO();
        ticket.setSeatType(1);
        ticket.setCarriageNumber("01");
        ticket.setSeatNumber("1A");
        return TicketSeatReservationDO.builder()
                .id(1L)
                .reservationId("reservation-a")
                .orderSn("order-1")
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
}
