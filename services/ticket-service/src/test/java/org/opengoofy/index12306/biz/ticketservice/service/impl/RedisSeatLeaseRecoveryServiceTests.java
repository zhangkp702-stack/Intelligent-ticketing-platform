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

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketSeatReservationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketSeatReservationMapper;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatLease;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.RedisSeatLeaseRecoveryMetrics;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Redis 临时占座租约只回收未进入 PREPARED 的 owner。
 */
class RedisSeatLeaseRecoveryServiceTests {

    /**
     * 已提交 PREPARED 的租约到期后只移除扫描索引，不能释放已锁定座位。
     */
    @Test
    void confirmsExpiredLeaseWhenDatabaseSeatWasPrepared() {
        RedisSeatBitmapService redisSeatBitmapService = mock(RedisSeatBitmapService.class);
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketSeatReservationReleaseService releaseService = mock(TicketSeatReservationReleaseService.class);
        RedisSeatLeaseRecoveryMetrics metrics = mock(RedisSeatLeaseRecoveryMetrics.class);
        RedisSeatLease lease = lease();
        when(redisSeatBitmapService.listExpiredLeases(10)).thenReturn(List.of(lease));
        when(reservationMapper.selectByReservationId("reservation-a")).thenReturn(TicketSeatReservationDO.builder()
                .reservationId("reservation-a")
                .reservationStatus(TicketSeatReservationReleaseService.RESERVATION_PREPARED)
                .build());
        RedisSeatLeaseRecoveryService service = service(redisSeatBitmapService, reservationMapper, releaseService, metrics);

        service.recoverExpiredLeases();

        verify(redisSeatBitmapService).confirmLease(lease);
        verify(redisSeatBitmapService, never()).releaseExpiredLease(lease);
        verify(releaseService, never()).releaseUnpreparedReservation("reservation-a");
        verify(metrics).refreshExpiredLeaseBatch(1);
    }

    /**
     * INIT 状态的租约到期后，必须使用 Redis 保存的座位位图上下文释放 owner 并推进库存补偿。
     */
    @Test
    void releasesExpiredLeaseAndAbortsInitialReservation() {
        RedisSeatBitmapService redisSeatBitmapService = mock(RedisSeatBitmapService.class);
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketSeatReservationReleaseService releaseService = mock(TicketSeatReservationReleaseService.class);
        RedisSeatLeaseRecoveryMetrics metrics = mock(RedisSeatLeaseRecoveryMetrics.class);
        RedisSeatLease lease = lease();
        when(redisSeatBitmapService.listExpiredLeases(10)).thenReturn(List.of(lease));
        when(reservationMapper.selectByReservationId("reservation-a")).thenReturn(TicketSeatReservationDO.builder()
                .reservationId("reservation-a")
                .reservationStatus(TicketSeatReservationReleaseService.RESERVATION_INIT)
                .build());
        RedisSeatLeaseRecoveryService service = service(redisSeatBitmapService, reservationMapper, releaseService, metrics);

        service.recoverExpiredLeases();

        verify(redisSeatBitmapService).releaseExpiredLease(lease);
        verify(releaseService).releaseUnpreparedReservation("reservation-a");
        verify(redisSeatBitmapService, never()).confirmLease(lease);
        verify(metrics).refreshExpiredLeaseBatch(1);
    }

    /**
     * Redis 扫描基础设施异常时，调度任务必须记录指标并正常返回，留给下一轮重试。
     */
    @Test
    void recordsScanFailureWithoutPropagatingException() {
        RedisSeatBitmapService redisSeatBitmapService = mock(RedisSeatBitmapService.class);
        TicketSeatReservationMapper reservationMapper = mock(TicketSeatReservationMapper.class);
        TicketSeatReservationReleaseService releaseService = mock(TicketSeatReservationReleaseService.class);
        RedisSeatLeaseRecoveryMetrics metrics = mock(RedisSeatLeaseRecoveryMetrics.class);
        when(redisSeatBitmapService.listExpiredLeases(10)).thenThrow(new IllegalStateException("redis unavailable"));
        RedisSeatLeaseRecoveryService service = service(redisSeatBitmapService, reservationMapper, releaseService, metrics);

        service.recoverExpiredLeases();

        verify(metrics).recordScanFailure();
        verify(reservationMapper, never()).selectByReservationId("reservation-a");
    }

    /**
     * 创建打开扫描开关的租约恢复服务。
     *
     * @param redisSeatBitmapService Redis 位图服务
     * @param reservationMapper 预订记录查询器
     * @param releaseService 预订补偿服务
     * @return 已注入测试配置的恢复服务
     */
    private RedisSeatLeaseRecoveryService service(RedisSeatBitmapService redisSeatBitmapService,
                                                  TicketSeatReservationMapper reservationMapper,
                                                  TicketSeatReservationReleaseService releaseService,
                                                  RedisSeatLeaseRecoveryMetrics metrics) {
        RedisSeatLeaseRecoveryService service = new RedisSeatLeaseRecoveryService(
                redisSeatBitmapService, reservationMapper, releaseService, metrics);
        ReflectionTestUtils.setField(service, "redisSeatLeaseRecoveryEnabled", true);
        ReflectionTestUtils.setField(service, "redisSeatLeaseRecoveryBatchSize", 10);
        return service;
    }

    /**
     * 构造可被租约扫描器重放的单车厢座位预占上下文。
     *
     * @return 已过期租约样例
     */
    private RedisSeatLease lease() {
        return RedisSeatLease.builder()
                .reservationId("reservation-a")
                .trainId("1")
                .serviceDateKey("20261009")
                .departure("A")
                .arrival("B")
                .seatType(1)
                .carriageNumber("01")
                .seatBits("0")
                .build();
    }
}
