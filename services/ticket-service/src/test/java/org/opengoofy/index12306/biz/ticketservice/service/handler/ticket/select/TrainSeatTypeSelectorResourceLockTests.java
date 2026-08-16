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
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证单通道车厢区间锁使用受控的毫秒等待、看门狗和逆序释放规则。
 */
class TrainSeatTypeSelectorResourceLockTests {

    /**
     * 区间锁必须最多等待 30ms 并启用 Redisson 看门狗，不能使用可能提前失效的固定租约。
     *
     * @throws InterruptedException Redisson 锁接口声明的中断异常
     */
    @Test
    void acquiresSegmentLockUsingMillisecondWaitAndWatchdog() throws InterruptedException {
        RedissonClient redissonClient = mock(RedissonClient.class);
        ConfigurableEnvironment environment = environment();
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(30L, TimeUnit.MILLISECONDS)).thenReturn(true);

        List<RLock> locks = acquire(selector(redissonClient, environment), List.of(2));

        assertEquals(List.of(lock), locks);
        verify(lock).tryLock(30L, TimeUnit.MILLISECONDS);
    }

    /**
     * 其中一个区间锁获取失败时，已经获取的较早锁必须被释放，防止锁泄漏。
     *
     * @throws InterruptedException Redisson 锁接口声明的中断异常
     */
    @Test
    void releasesAcquiredLocksWhenLaterSegmentLockTimesOut() throws InterruptedException {
        RedissonClient redissonClient = mock(RedissonClient.class);
        ConfigurableEnvironment environment = environment();
        RLock firstLock = mock(RLock.class);
        RLock secondLock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(firstLock, secondLock);
        when(firstLock.tryLock(30L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(secondLock.tryLock(30L, TimeUnit.MILLISECONDS)).thenReturn(false);
        when(firstLock.isHeldByCurrentThread()).thenReturn(true);

        List<RLock> locks = acquire(selector(redissonClient, environment), List.of(1, 2));

        assertEquals(List.of(), locks);
        verify(firstLock).unlock();
    }

    /**
     * 正常路径释放多个区间锁时必须按获取的反方向执行，保持嵌套锁的释放顺序稳定。
     *
     * @throws InterruptedException Redisson 锁接口声明的中断异常
     */
    @Test
    void releasesSegmentLocksInReverseAcquisitionOrder() throws InterruptedException {
        RedissonClient redissonClient = mock(RedissonClient.class);
        ConfigurableEnvironment environment = environment();
        RLock firstLock = mock(RLock.class);
        RLock secondLock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(firstLock, secondLock);
        when(firstLock.tryLock(30L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(secondLock.tryLock(30L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(firstLock.isHeldByCurrentThread()).thenReturn(true);
        when(secondLock.isHeldByCurrentThread()).thenReturn(true);
        TrainSeatTypeSelector selector = selector(redissonClient, environment);

        List<RLock> locks = acquire(selector, List.of(1, 2));
        ReflectionTestUtils.invokeMethod(selector, "releaseSegmentLocks", locks);

        var inOrder = inOrder(firstLock, secondLock);
        inOrder.verify(secondLock).unlock();
        inOrder.verify(firstLock).unlock();
    }

    /**
     * 整体选座时间已耗尽时，不能再发起任何 Redis 锁调用，避免超出请求预算后继续排队。
     */
    @Test
    void skipsLockAcquisitionWhenSelectionDeadlineHasExpired() {
        RedissonClient redissonClient = mock(RedissonClient.class);

        List<RLock> locks = acquireWithDeadline(selector(redissonClient, environment()), List.of(1),
                System.nanoTime() - 1L);

        assertEquals(List.of(), locks);
        verifyNoInteractions(redissonClient);
    }

    /**
     * 相同 reservation 必须稳定旋转候选车厢，同时不能修改余票摘要生成的原始候选顺序。
     */
    @Test
    void rotatesCandidateCarriagesStablyWithoutMutatingOriginalList() {
        TrainSeatTypeSelector selector = selector(mock(RedissonClient.class), environment());
        List<CarriageAvailabilityDTO> candidates = List.of(
                new CarriageAvailabilityDTO("01", 10),
                new CarriageAvailabilityDTO("02", 9),
                new CarriageAvailabilityDTO("03", 8)
        );

        List<CarriageAvailabilityDTO> firstOrder = orderCarriages(selector, candidates, "a");
        List<CarriageAvailabilityDTO> secondOrder = orderCarriages(selector, candidates, "a");

        assertEquals(List.of("01", "02", "03"), candidates.stream()
                .map(CarriageAvailabilityDTO::getCarriageNumber).toList());
        assertEquals(List.of("02", "03", "01"), firstOrder.stream()
                .map(CarriageAvailabilityDTO::getCarriageNumber).toList());
        assertEquals(firstOrder, secondOrder);
    }

    /**
     * 调用私有区间锁获取方法，固定请求维度以便测试锁参数与释放行为。
     *
     * @param selector 待测试的座位选择器
     * @param segmentIndexes 区间段索引
     * @return 获取成功的锁集合
     */
    @SuppressWarnings("unchecked")
    private List<RLock> acquire(TrainSeatTypeSelector selector, List<Integer> segmentIndexes) {
        return ReflectionTestUtils.invokeMethod(selector, "tryAcquireCarriageSegmentLocks",
                "1001", new Date(1_723_494_400_000L), 1, "01", segmentIndexes);
    }

    /**
     * 调用带整体截止时间的锁获取重载，验证超时边界不会继续访问 Redis。
     *
     * @param selector 待测试的座位选择器
     * @param segmentIndexes 区间段索引
     * @param selectionDeadlineNanos 选座整体截止时间
     * @return 获取成功的锁集合
     */
    @SuppressWarnings("unchecked")
    private List<RLock> acquireWithDeadline(TrainSeatTypeSelector selector, List<Integer> segmentIndexes,
                                            long selectionDeadlineNanos) {
        return ReflectionTestUtils.invokeMethod(selector, "tryAcquireCarriageSegmentLocks",
                "1001", new Date(1_723_494_400_000L), 1, "01", segmentIndexes, selectionDeadlineNanos);
    }

    /**
     * 调用候选车厢稳定排序方法，验证热点请求的起点分散规则。
     *
     * @param selector 待测试的座位选择器
     * @param candidates 原始候选车厢
     * @param reservationId 当前预占标识
     * @return 旋转后的候选车厢顺序
     */
    @SuppressWarnings("unchecked")
    private List<CarriageAvailabilityDTO> orderCarriages(TrainSeatTypeSelector selector,
                                                           List<CarriageAvailabilityDTO> candidates,
                                                           String reservationId) {
        return ReflectionTestUtils.invokeMethod(selector, "orderCandidateCarriages", candidates, reservationId);
    }

    /**
     * 构造只包含区间锁依赖的座位选择器。
     *
     * @param redissonClient Redisson 客户端 mock
     * @param environment 配置环境 mock
     * @return 可调用区间锁私有方法的选择器
     */
    private TrainSeatTypeSelector selector(RedissonClient redissonClient, ConfigurableEnvironment environment) {
        return new TrainSeatTypeSelector(null, null, null, null, null, null, null, redissonClient, environment,
                null, null, new SeatSelectionStrategySharedSelector(mock(SeatSelectionStrategyRedisStore.class),
                mock(SeatSelectionStrategySelector.class), mock(TicketPurchaseMetrics.class)), null);
    }

    /**
     * 构造原样返回锁键的环境，避免占位符解析干扰锁获取测试。
     *
     * @return 配置环境 mock
     */
    private ConfigurableEnvironment environment() {
        ConfigurableEnvironment environment = mock(ConfigurableEnvironment.class);
        when(environment.resolvePlaceholders(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        return environment;
    }
}
