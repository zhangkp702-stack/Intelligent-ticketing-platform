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
 * <p>近期 Redis 临时占位冲突率超过阈值时，优先改用车厢区间锁单通道；余票较低只作为样本不足时的安全兜底，
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

    @Value("${ticket.seat.selection.conflict-rate-threshold:0.70}")
    private double conflictRateThreshold = 0.70D;

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
        // 先按同运行库存的 Redis 实际占位冲突率路由；该指标是本策略的主判断条件。
        ConflictWindow conflictWindow = conflictWindowCache.getIfPresent(buildConflictWindowKey(requestParam, seatType));
        if (conflictWindow != null && conflictWindow.shouldUseSingleChannel(
                conflictWindowMinAttempts, conflictRateThreshold)) {
            return true;
        }
        // 样本不足或冲突率未达到阈值时，才以车厢余票总量作为安全兜底。
        int availableSeats = candidateCarriages.stream().mapToInt(CarriageAvailabilityDTO::getSeatCount).sum();
        return availableSeats <= lowStockThreshold;
    }

    /**
     * 记录一次 Redis Lua 临时占位的直接结果，为后续请求的策略切换提供低成本窗口指标。
     *
     * @param requestParam 已校验的购票请求
     * @param seatType 当前座位类型
     * @param conflict Redis Lua 是否因座位已被其它请求占用而返回冲突
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
         * @param minimumConflictRate 触发单通道的冲突率阈值
         * @return 是否应切换为单通道
         */
        private boolean shouldUseSingleChannel(int minimumAttempts, double minimumConflictRate) {
            // 未达到最小样本量时不切换，避免少量偶发冲突降低高余票吞吐。
            long attemptCount = attempts.sum();
            if (attemptCount < minimumAttempts) {
                return false;
            }
            // 只有冲突率严格超过阈值才降级，百分之七十本身仍保留乐观通道吞吐。
            return (double) conflicts.sum() / attemptCount > minimumConflictRate;
        }
    }
}
