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

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageAvailabilityDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Redis 共享状态机的选座路由、短时缓存和单 reservation 上报边界。
 */
class SeatSelectionStrategySharedSelectorTests {

    /**
     * Redis 已迁移至单通道时，当前请求必须直接进入区间锁路径。
     */
    @Test
    void usesSingleChannelWhenSharedStateIsSingle() {
        StrategyFixture fixture = fixture();
        stubState(fixture, state(SeatSelectionStrategyMode.SINGLE, 0));

        SeatSelectionRoute route = fixture.selector.decide(fixture.request, 1, carriages(100), "reservation-a");

        assertTrue(route.useSingleChannel());
    }

    /**
     * Redis 已保持乐观状态时，正常请求必须记录为 normal 样本。
     */
    @Test
    void usesNormalSampleWhenSharedStateIsOptimistic() {
        StrategyFixture fixture = fixture();
        stubState(fixture, state(SeatSelectionStrategyMode.OPTIMISTIC, 100));

        SeatSelectionRoute route = fixture.selector.decide(fixture.request, 1, carriages(100), "reservation-a");

        assertFalse(route.useSingleChannel());
        assertEquals(SeatSelectionSampleType.NORMAL, route.sampleType());
    }

    /**
     * 探测状态必须依据 reservationId 稳定哈希分流，并把放行请求记为 probe 样本。
     */
    @Test
    void routesProbingTrafficByStableReservationHash() {
        StrategyFixture fixture = fixture();
        stubState(fixture, state(SeatSelectionStrategyMode.PROBING, 5));
        String optimisticReservationId = reservationForBucketBelow(5);
        String singleReservationId = reservationForBucketAtLeast(5);

        SeatSelectionRoute optimisticRoute = fixture.selector.decide(
                fixture.request, 1, carriages(100), optimisticReservationId);
        SeatSelectionRoute singleRoute = fixture.selector.decide(
                fixture.request, 1, carriages(100), singleReservationId);

        assertFalse(optimisticRoute.useSingleChannel());
        assertEquals(SeatSelectionSampleType.PROBE, optimisticRoute.sampleType());
        assertTrue(singleRoute.useSingleChannel());
    }

    /**
     * 渐进恢复状态同样必须使用稳定哈希，且扩大比例不能把样本误记为 normal。
     */
    @Test
    void routesRecoveringTrafficIntoProbeSample() {
        StrategyFixture fixture = fixture();
        stubState(fixture, state(SeatSelectionStrategyMode.RECOVERING, 60));
        String optimisticReservationId = reservationForBucketBelow(60);

        SeatSelectionRoute route = fixture.selector.decide(
                fixture.request, 1, carriages(100), optimisticReservationId);

        assertFalse(route.useSingleChannel());
        assertEquals(SeatSelectionSampleType.PROBE, route.sampleType());
    }

    /**
     * 同一库存维度短时间内重复决策时，应复用 Redis 状态快照而非重复执行迁移脚本。
     */
    @Test
    void cachesSharedStateSnapshotForShortDecisionWindow() {
        StrategyFixture fixture = fixture();
        stubState(fixture, state(SeatSelectionStrategyMode.OPTIMISTIC, 100));

        fixture.selector.decide(fixture.request, 1, carriages(100), "reservation-a");
        fixture.selector.decide(fixture.request, 1, carriages(100), "reservation-b");

        verify(fixture.redisStore).transition(anyString(), any(SeatConflictStatistics.class),
                any(SeatConflictStatistics.class), anyInt(), any(SeatSelectionStrategyStateConfig.class), anyLong());
    }

    /**
     * Redis 共享状态读取异常时，应回退本机窗口且不得阻断购票选座。
     */
    @Test
    void fallsBackToLocalWindowWhenSharedStateReadFails() {
        StrategyFixture fixture = fixture();
        when(fixture.redisStore.snapshot(anyString(), eq(SeatSelectionWindow.NORMAL_FAST), anyLong()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(fixture.localSelector.shouldUseSingleChannel(eq(fixture.request), eq(1), anyList())).thenReturn(true);

        SeatSelectionRoute route = fixture.selector.decide(fixture.request, 1, carriages(100), "reservation-a");

        assertTrue(route.useSingleChannel());
        verify(fixture.ticketPurchaseMetrics).recordFailure("strategy_statistics", "redis_error");
    }

    /**
     * 同一 reservation 的多次座位重试只能上报一次，避免单个请求放大共享冲突率。
     */
    @Test
    void recordsOnlyOneContributionForSameReservation() {
        StrategyFixture fixture = fixture();

        fixture.selector.recordOptimisticSelectionResult(fixture.request, 1, "reservation-a", true);
        fixture.selector.recordOptimisticSelectionResult(fixture.request, 1, "reservation-a", false);

        verify(fixture.redisStore).record(anyString(), eq(SeatSelectionSampleType.NORMAL), eq("reservation-a"),
                eq(true), anyLong());
        verify(fixture.localSelector).recordOptimisticSelectionResult(fixture.request, 1, true);
    }

    /**
     * Redis 统计写入失败只能影响可观测性，不能把成功或冲突的占位结果转成系统异常。
     */
    @Test
    void ignoresSharedStatisticsRecordFailure() {
        StrategyFixture fixture = fixture();
        doThrow(new IllegalStateException("redis unavailable"))
                .when(fixture.redisStore)
                .record(anyString(), eq(SeatSelectionSampleType.NORMAL), eq("reservation-a"), eq(false), anyLong());

        assertDoesNotThrow(() -> fixture.selector.recordOptimisticSelectionResult(
                fixture.request, 1, "reservation-a", false));

        verify(fixture.ticketPurchaseMetrics).recordFailure("strategy_statistics", "redis_error");
    }

    /**
     * 探测路由的占位结果必须写入 probe 桶，不能污染 normal 的降级窗口。
     */
    @Test
    void recordsProbeResultIntoProbeSample() {
        StrategyFixture fixture = fixture();

        fixture.selector.recordOptimisticSelectionResult(
                fixture.request, 1, "reservation-a", SeatSelectionSampleType.PROBE, false);

        verify(fixture.redisStore).record(anyString(), eq(SeatSelectionSampleType.PROBE), eq("reservation-a"),
                eq(false), anyLong());
    }

    /**
     * 为同一测试夹具预置 Redis Lua 返回的共享状态。
     *
     * @param fixture 当前测试夹具
     * @param state 需要由迁移脚本返回的状态
     */
    private void stubState(StrategyFixture fixture, SeatSelectionStrategyState state) {
        when(fixture.redisStore.transition(anyString(), any(SeatConflictStatistics.class),
                any(SeatConflictStatistics.class), anyInt(), any(SeatSelectionStrategyStateConfig.class), anyLong()))
                .thenReturn(state);
    }

    /**
     * 构造 hash bucket 小于给定百分比的 reservationId。
     *
     * @param percentage 允许乐观占位的百分比
     * @return 可稳定命中乐观探测流量的 reservationId
     */
    private String reservationForBucketBelow(int percentage) {
        for (int index = 0; index < 1_000; index++) {
            String reservationId = "probe-" + index;
            if (Math.floorMod(reservationId.hashCode(), 100) < percentage) {
                return reservationId;
            }
        }
        throw new IllegalStateException("unable to build probing reservation id");
    }

    /**
     * 构造 hash bucket 不小于给定百分比的 reservationId。
     *
     * @param percentage 允许乐观占位的百分比
     * @return 可稳定命中单通道路由的 reservationId
     */
    private String reservationForBucketAtLeast(int percentage) {
        for (int index = 0; index < 1_000; index++) {
            String reservationId = "single-" + index;
            if (Math.floorMod(reservationId.hashCode(), 100) >= percentage) {
                return reservationId;
            }
        }
        throw new IllegalStateException("unable to build single-channel reservation id");
    }

    /**
     * 创建用于路由测试的固定共享状态。
     *
     * @param mode 当前状态模式
     * @param optimisticPercentage 当前允许乐观占位的比例
     * @return 固定版本的共享策略状态
     */
    private SeatSelectionStrategyState state(SeatSelectionStrategyMode mode, int optimisticPercentage) {
        return new SeatSelectionStrategyState(mode, 1L, 1L, 1L, optimisticPercentage, 0, "test");
    }

    /**
     * 构造共享策略选择器及其三个协作依赖。
     *
     * @return 可独立验证共享统计行为的测试夹具
     */
    private StrategyFixture fixture() {
        SeatSelectionStrategyRedisStore redisStore = mock(SeatSelectionStrategyRedisStore.class);
        SeatSelectionStrategySelector localSelector = mock(SeatSelectionStrategySelector.class);
        TicketPurchaseMetrics ticketPurchaseMetrics = mock(TicketPurchaseMetrics.class);
        SeatSelectionStrategySharedSelector selector = new SeatSelectionStrategySharedSelector(
                redisStore, localSelector, ticketPurchaseMetrics);
        return new StrategyFixture(selector, redisStore, localSelector, ticketPurchaseMetrics, request());
    }

    /**
     * 创建固定运行日和区间的请求，确保每个断言使用同一共享策略维度。
     *
     * @return 已填充策略键字段的购票请求
     */
    private PurchaseTicketReqDTO request() {
        PurchaseTicketReqDTO request = new PurchaseTicketReqDTO();
        request.setTrainId("1001");
        request.setServiceDate(new Date(1_723_494_400_000L));
        request.setDeparture("A");
        request.setArrival("B");
        return request;
    }

    /**
     * 创建一个给定余票的候选车厢集合。
     *
     * @param seatCount 车厢可售座位数
     * @return 单车厢余票摘要
     */
    private List<CarriageAvailabilityDTO> carriages(int seatCount) {
        return List.of(new CarriageAvailabilityDTO("01", seatCount));
    }

    private record StrategyFixture(SeatSelectionStrategySharedSelector selector,
                                   SeatSelectionStrategyRedisStore redisStore,
                                   SeatSelectionStrategySelector localSelector,
                                   TicketPurchaseMetrics ticketPurchaseMetrics,
                                   PurchaseTicketReqDTO request) {
    }
}
