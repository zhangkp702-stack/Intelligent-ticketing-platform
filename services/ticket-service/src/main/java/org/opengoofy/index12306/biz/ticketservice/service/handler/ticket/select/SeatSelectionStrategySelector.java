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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageAvailabilityDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 根据余票规模和近期乐观占位冲突率选择座位分配通道。
 *
 * <p>高余票时采用 Redis 临时占位获得较高并发；余票较低或近期冲突率很高时，改用车厢区间锁单通道，
 * 以减少多个请求重复扫描相同候选座位造成的无效回滚。</p>
 */
@Component
public class SeatSelectionStrategySelector {

    private static final long CONFLICT_WINDOW_TTL_SECONDS = 60L;

    private final Cache<String, ConflictWindow> conflictWindowCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(CONFLICT_WINDOW_TTL_SECONDS, TimeUnit.SECONDS)
            .build();

    @Value("${ticket.seat.selection.low-stock-threshold:40}")
    private int lowStockThreshold = 40;

    @Value("${ticket.seat.selection.conflict-window-min-attempts:20}")
    private int conflictWindowMinAttempts = 20;

    @Value("${ticket.seat.selection.conflict-rate-threshold:0.80}")
    private double conflictRateThreshold = 0.80D;

    /**
     * 判断本次选座是否应跳过乐观 Redis 占位，直接进入区间锁单通道。
     *
     * @param requestParam 已校验的购票请求
     * @param seatType 当前座位类型
     * @param candidateCarriages 当前候选车厢余票摘要
     * @return true 表示采用单通道，false 表示采用 Redis 乐观占位
     */
    public boolean shouldUseSingleChannel(PurchaseTicketReqDTO requestParam,
                                          Integer seatType,
                                          List<CarriageAvailabilityDTO> candidateCarriages) {
        // 汇总候选车厢的剩余量；该摘要只是路由依据，最终正确性仍由库存位图 CAS 保证。
        int availableSeats = candidateCarriages.stream().mapToInt(CarriageAvailabilityDTO::getSeatCount).sum();
        if (availableSeats <= lowStockThreshold) {
            return true;
        }
        // 同一开行日、区间和座位类型独立统计，避免一趟车的热点影响其它运行库存。
        ConflictWindow conflictWindow = conflictWindowCache.getIfPresent(buildConflictWindowKey(requestParam, seatType));
        return conflictWindow != null && conflictWindow.shouldUseSingleChannel(
                conflictWindowMinAttempts, conflictRateThreshold);
    }

    /**
     * 记录一次 Redis 乐观占位的最终结果，为后续请求的策略切换提供低成本窗口指标。
     *
     * @param requestParam 已校验的购票请求
     * @param seatType 当前座位类型
     * @param conflict 是否因座位竞争而未能完成 Redis 通道选座
     */
    public void recordOptimisticSelectionResult(PurchaseTicketReqDTO requestParam, Integer seatType, boolean conflict) {
        // 使用固定时间窗口防止一次短暂波动永久将该区间固定在单通道模式。
        ConflictWindow conflictWindow = conflictWindowCache.get(
                buildConflictWindowKey(requestParam, seatType), key -> new ConflictWindow());
        conflictWindow.record(conflict);
    }

    /**
     * 生成同一运行库存下的冲突统计键。
     *
     * @param requestParam 已校验的购票请求
     * @param seatType 当前座位类型
     * @return 固定维度的本地统计键
     */
    private String buildConflictWindowKey(PurchaseTicketReqDTO requestParam, Integer seatType) {
        // 始发日期必须参与键构造，避免不同开行日的竞争状态相互污染。
        return ServiceDateKeyUtil.buildKey(requestParam.getTrainId(), requestParam.getServiceDate(),
                requestParam.getDeparture(), requestParam.getArrival(), String.valueOf(seatType));
    }

    /**
     * 保存一个短时间内的乐观选座尝试数和冲突数。
     */
    private static final class ConflictWindow {

        private final LongAdder attempts = new LongAdder();
        private final LongAdder conflicts = new LongAdder();

        /**
         * 追加一次乐观选座结果。
         *
         * @param conflict 是否发生资源竞争
         */
        private void record(boolean conflict) {
            // LongAdder 仅服务于进程内策略路由，不参与座位库存正确性判断。
            attempts.increment();
            if (conflict) {
                conflicts.increment();
            }
        }

        /**
         * 根据当前窗口尝试次数和冲突率决定是否降级。
         *
         * @param minimumAttempts 生效前的最少尝试次数
         * @param minimumConflictRate 触发单通道的最小冲突率
         * @return 是否应切换为单通道
         */
        private boolean shouldUseSingleChannel(int minimumAttempts, double minimumConflictRate) {
            // 未达到最小样本量时不切换，避免少量偶发冲突降低高余票吞吐。
            long attemptCount = attempts.sum();
            if (attemptCount < minimumAttempts) {
                return false;
            }
            return (double) conflicts.sum() / attemptCount >= minimumConflictRate;
        }
    }
}
