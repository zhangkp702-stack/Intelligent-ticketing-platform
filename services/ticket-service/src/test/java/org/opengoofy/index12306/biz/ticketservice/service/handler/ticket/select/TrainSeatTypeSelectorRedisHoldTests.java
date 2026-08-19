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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageAvailabilityDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PurchaseSeatContext;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.SelectSeatDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证选座结果返回前发生异常时，Redis bitmap 与 owner 会按 reservationId 得到补偿。
 */
class TrainSeatTypeSelectorRedisHoldTests {

    private final ThreadPoolExecutor selectSeatExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

    /**
     * 关闭测试创建的专用线程池，避免遗留非守护线程影响模块测试退出。
     */
    @AfterEach
    void tearDown() {
        // 单席别测试不会提交异步任务，仍主动关闭线程池以保持测试资源边界清晰。
        selectSeatExecutor.shutdownNow();
    }

    /**
     * Redis 占位成功后数据库锁座异常，必须在向上抛出原异常前释放当前 owner。
     */
    @Test
    void releasesRedisHoldWhenDatabaseLockThrows() {
        SelectorFixture fixture = fixture();
        when(fixture.seatService.tryLockSeat(anyString(), any(Date.class), anyString(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> fixture.selector.select(
                0, fixture.request, "reservation-a", fixture.purchaseContext));

        verify(fixture.redisSeatBitmapService).releaseHeld(eq("1001"), eq(fixture.request.getServiceDate()),
                eq("A"), eq("B"), eq(List.of(fixture.ticket)));
    }

    /**
     * 数据库锁座成功后车厢余票摘要更新失败，也必须释放 Redis owner 供事务回滚后的后续请求重试。
     */
    @Test
    void releasesRedisHoldWhenCarriageSummaryUpdateThrows() {
        SelectorFixture fixture = fixture();
        when(fixture.seatService.tryLockSeat(anyString(), any(Date.class), anyString(), anyString(), anyList()))
                .thenReturn(true);
        doThrow(new IllegalStateException("summary unavailable"))
                .when(fixture.seatService)
                .adjustCarriageRemainingSummary(anyString(), any(Date.class), anyString(), anyString(), anyInt(), anyString(), anyLong());

        assertThrows(IllegalStateException.class, () -> fixture.selector.select(
                0, fixture.request, "reservation-a", fixture.purchaseContext));

        verify(fixture.redisSeatBitmapService).releaseHeld(eq("1001"), eq(fixture.request.getServiceDate()),
                eq("A"), eq("B"), eq(List.of(fixture.ticket)));
    }

    /**
     * 乘车人预加载失败时必须在进入锁座前终止，不得写入 Redis owner。
     */
    @Test
    void passengerPreparationFailureShouldNotEnterSeatSelection() {
        SelectorFixture fixture = fixture();
        when(fixture.userRemoteService.listPassengerQueryByIds(any(), anyList()))
                .thenThrow(new IllegalStateException("user service unavailable"));

        // 乘车人权威数据未准备完成时，锁座流程尚未开始，因此没有需要补偿的 owner。
        assertThrows(IllegalStateException.class, () -> fixture.selector.preparePurchaseContext(fixture.request));

        verify(fixture.redisSeatBitmapService, never()).tryHold(
                anyString(), any(Date.class), anyString(), anyString(), anyInt(), anyList(), anyString());
    }

    /**
     * 多席别任务中任一任务失败时，必须等待其它任务结束并补偿全部已写入的 Redis owner。
     */
    @Test
    void releasesAllRedisHoldsWhenAnotherSeatTypeFails() {
        SelectorFixture fixture = fixture();
        PurchaseTicketPassengerDetailDTO secondPassenger = new PurchaseTicketPassengerDetailDTO();
        secondPassenger.setPassengerId("passenger-2");
        secondPassenger.setSeatType(2);
        fixture.request.setPassengers(List.of(fixture.request.getPassengers().get(0), secondPassenger));
        when(fixture.seatService.listCandidateCarriages(eq("1001"), eq(fixture.request.getServiceDate()), anyInt(), eq("A"), eq("B"), eq(1)))
                .thenReturn(List.of(new CarriageAvailabilityDTO("01", 100)));
        when(fixture.abstractStrategyChoose.<SelectSeatDTO, List<TrainPurchaseTicketRespDTO>>chooseAndExecuteResp(anyString(), any(SelectSeatDTO.class)))
                .thenAnswer(invocation -> {
                    SelectSeatDTO selectSeat = invocation.getArgument(1);
                    TrainPurchaseTicketRespDTO selectedTicket = ticket();
                    selectedTicket.setPassengerId("passenger-" + selectSeat.getSeatType());
                    selectedTicket.setSeatType(selectSeat.getSeatType());
                    return List.of(selectedTicket);
                });
        when(fixture.redisSeatBitmapService.tryHold(eq("1001"), eq(fixture.request.getServiceDate()), eq("A"), eq("B"), anyInt(), anyList(), eq("reservation-a")))
                .thenAnswer(invocation -> {
                    List<TrainPurchaseTicketRespDTO> selectedTickets = invocation.getArgument(5);
                    selectedTickets.forEach(each -> each.setRedisSeatHoldId("reservation-a"));
                    return "reservation-a";
                });
        when(fixture.seatService.tryLockSeat(anyString(), any(Date.class), anyString(), anyString(), anyList()))
                .thenAnswer(invocation -> {
                    List<TrainPurchaseTicketRespDTO> selectedTickets = invocation.getArgument(4);
                    if (selectedTickets.get(0).getSeatType() == 2) {
                        throw new IllegalStateException("database unavailable");
                    }
                    return true;
                });

        assertThrows(RuntimeException.class, () -> fixture.selector.select(
                0, fixture.request, "reservation-a", fixture.purchaseContext));

        ArgumentCaptor<List<TrainPurchaseTicketRespDTO>> heldTicketsCaptor = ArgumentCaptor.forClass(List.class);
        verify(fixture.redisSeatBitmapService).releaseHeld(eq("1001"), eq(fixture.request.getServiceDate()),
                eq("A"), eq("B"), heldTicketsCaptor.capture());
        assertEquals(2, heldTicketsCaptor.getValue().size());
    }

    /**
     * Redis owner 已写入后指标记录失败时，补偿必须继续执行且指标异常不能阻断释放。
     */
    @Test
    void releasesRedisHoldWhenMetricsRecordThrows() {
        SelectorFixture fixture = fixture();
        // 只在 Redis owner 已写入后的占位指标阶段注入异常，避免前置观测阶段提前终止目标场景。
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(fixture.ticketPurchaseMetrics)
                .recordStage(any(), eq("redis_hold"), anyString());

        assertThrows(IllegalStateException.class, () -> fixture.selector.select(
                0, fixture.request, "reservation-a", fixture.purchaseContext));

        verify(fixture.redisSeatBitmapService).releaseHeld(eq("1001"), eq(fixture.request.getServiceDate()),
                eq("A"), eq("B"), eq(List.of(fixture.ticket)));
    }

    /**
     * 构造单席别乐观 Redis 选座依赖，并让 Redis mock 写回 owner 以模拟真实 tryHold 行为。
     *
     * @return 包含请求、候选座位和依赖 mock 的测试夹具
     */
    @SuppressWarnings("unchecked")
    private SelectorFixture fixture() {
        SeatService seatService = mock(SeatService.class);
        UserRemoteService userRemoteService = mock(UserRemoteService.class);
        TrainStationPriceMapper trainStationPriceMapper = mock(TrainStationPriceMapper.class);
        AbstractStrategyChoose abstractStrategyChoose = mock(AbstractStrategyChoose.class);
        DistributedCache distributedCache = mock(DistributedCache.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        TrainStationService trainStationService = mock(TrainStationService.class);
        RedisSeatBitmapService redisSeatBitmapService = mock(RedisSeatBitmapService.class);
        TicketPurchaseMetrics ticketPurchaseMetrics = mock(TicketPurchaseMetrics.class);
        SeatSelectionStrategyRedisStore seatSelectionStrategyRedisStore = mock(SeatSelectionStrategyRedisStore.class);
        TrainSeatTypeSelector selector = new TrainSeatTypeSelector(
                seatService,
                userRemoteService,
                trainStationPriceMapper,
                abstractStrategyChoose,
                selectSeatExecutor,
                distributedCache,
                trainStationService,
                null,
                null,
                redisSeatBitmapService,
                ticketPurchaseMetrics,
                new SeatSelectionStrategySharedSelector(seatSelectionStrategyRedisStore,
                        new SeatSelectionStrategySelector(), ticketPurchaseMetrics),
                null);
        ReflectionTestUtils.setField(selector, "redisSeatBitmapEnabled", true);
        // 乐观选座在 Redis 占位前会推进车厢扫描游标，测试必须提供该轻量依赖以进入目标异常分支。
        when(distributedCache.getInstance()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        PurchaseTicketReqDTO request = request();
        TrainPurchaseTicketRespDTO ticket = ticket();
        when(seatService.listCandidateCarriages(eq("1001"), eq(request.getServiceDate()), eq(1), eq("A"), eq("B"), eq(1)))
                .thenReturn(List.of(new CarriageAvailabilityDTO("01", 100)));
        when(abstractStrategyChoose.<SelectSeatDTO, List<TrainPurchaseTicketRespDTO>>chooseAndExecuteResp(anyString(), any(SelectSeatDTO.class)))
                .thenReturn(List.of(ticket));
        when(redisSeatBitmapService.tryHold(eq("1001"), eq(request.getServiceDate()), eq("A"), eq("B"), eq(1), anyList(), eq("reservation-a")))
                .thenAnswer(invocation -> {
                    List<TrainPurchaseTicketRespDTO> selectedTickets = invocation.getArgument(5);
                    // 模拟真实 Redis 服务将 owner 写入座位明细，供 releaseHeld 使用相同 reservationId 校验。
                    selectedTickets.forEach(each -> each.setRedisSeatHoldId("reservation-a"));
                    return "reservation-a";
                });
        when(trainStationService.listTakeoutTrainStationRoute(eq("1001"), eq("A"), eq("B"))).thenReturn(List.of());
        PassengerRespDTO firstPassenger = passenger("passenger-1");
        PassengerRespDTO secondPassenger = passenger("passenger-2");
        PurchaseSeatContext purchaseContext = new PurchaseSeatContext(
                Map.of("passenger-1", firstPassenger, "passenger-2", secondPassenger),
                Map.of(1, 10000, 2, 20000));
        return new SelectorFixture(selector, request, ticket, purchaseContext, seatService, userRemoteService,
                redisSeatBitmapService, abstractStrategyChoose, ticketPurchaseMetrics);
    }

    /**
     * 构造可用于内存补齐的乘车人快照。
     *
     * @param passengerId 乘车人标识
     * @return 当前用户的乘车人快照
     */
    private PassengerRespDTO passenger(String passengerId) {
        // 测试只关心选座后的内存映射，使用固定非敏感字段即可。
        PassengerRespDTO passenger = new PassengerRespDTO();
        passenger.setId(passengerId);
        passenger.setRealName(passengerId);
        passenger.setIdType(0);
        passenger.setIdCard("test-card-" + passengerId);
        passenger.setPhone("13800000000");
        passenger.setDiscountType(0);
        return passenger;
    }

    /**
     * 构造固定始发日期的单乘车人请求，保证测试命中同一运行库存维度。
     *
     * @return 乐观选座请求
     */
    private PurchaseTicketReqDTO request() {
        PurchaseTicketPassengerDetailDTO passenger = new PurchaseTicketPassengerDetailDTO();
        passenger.setPassengerId("passenger-1");
        passenger.setSeatType(1);
        PurchaseTicketReqDTO request = new PurchaseTicketReqDTO();
        request.setTrainId("1001");
        request.setServiceDate(new Date(1_723_494_400_000L));
        request.setDeparture("A");
        request.setArrival("B");
        request.setPassengers(List.of(passenger));
        return request;
    }

    /**
     * 构造策略选择返回的一张座位，用于验证 owner 条件释放的参数。
     *
     * @return 已选座位
     */
    private TrainPurchaseTicketRespDTO ticket() {
        TrainPurchaseTicketRespDTO ticket = new TrainPurchaseTicketRespDTO();
        ticket.setPassengerId("passenger-1");
        ticket.setSeatType(1);
        ticket.setCarriageNumber("01");
        ticket.setSeatNumber("1A");
        return ticket;
    }

    private record SelectorFixture(TrainSeatTypeSelector selector,
                                   PurchaseTicketReqDTO request,
                                   TrainPurchaseTicketRespDTO ticket,
                                   PurchaseSeatContext purchaseContext,
                                   SeatService seatService,
                                   UserRemoteService userRemoteService,
                                   RedisSeatBitmapService redisSeatBitmapService,
                                   AbstractStrategyChoose abstractStrategyChoose,
                                   TicketPurchaseMetrics ticketPurchaseMetrics) {
    }
}
