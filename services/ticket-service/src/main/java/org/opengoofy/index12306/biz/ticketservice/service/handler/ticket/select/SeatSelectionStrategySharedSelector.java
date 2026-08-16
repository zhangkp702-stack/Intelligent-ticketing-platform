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

import cn.hutool.core.util.StrUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageAvailabilityDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 共享冲突统计选择座位分配通道，并在 Redis 统计不可用时回退本机窗口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatSelectionStrategySharedSelector {

    private static final long SNAPSHOT_CACHE_MILLIS = 200L;
    private static final long RESERVATION_CONTRIBUTION_CACHE_SECONDS = 5L;

    private final SeatSelectionStrategyRedisStore seatSelectionStrategyRedisStore;
    private final SeatSelectionStrategySelector localSeatSelectionStrategySelector;
    private final TicketPurchaseMetrics ticketPurchaseMetrics;

    private final Cache<String, SeatSelectionStrategyState> stateSnapshotCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(SNAPSHOT_CACHE_MILLIS, TimeUnit.MILLISECONDS)
            .build();

    private final Cache<String, Boolean> reservationContributionCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(RESERVATION_CONTRIBUTION_CACHE_SECONDS, TimeUnit.SECONDS)
            .build();

    @Value("${ticket.seat.selection.low-stock-threshold:40}")
    private int lowStockThreshold = 40;

    @Value("${ticket.seat.selection.conflict-window-min-attempts:20}")
    private int conflictWindowMinAttempts = 20;

    @Value("${ticket.seat.selection.conflict-rate-threshold:0.70}")
    private double conflictRateThreshold = 0.70D;

    @Value("${ticket.seat.selection.recovery-conflict-rate-threshold:0.50}")
    private double recoveryConflictRateThreshold = 0.50D;

    @Value("${ticket.seat.selection.state-evaluation-interval-millis:500}")
    private long stateEvaluationIntervalMillis = 500L;

    @Value("${ticket.seat.selection.single-minimum-residence-millis:5000}")
    private long singleMinimumResidenceMillis = 5_000L;

    @Value("${ticket.seat.selection.probe-percentage:5}")
    private int probePercentage = 5;

    @Value("${ticket.seat.selection.recovery-healthy-periods:3}")
    private int recoveryHealthyPeriods = 3;

    /**
     * 按共享冲突统计决定本次选座通道，Redis 统计异常时使用本机统计兼容回退。
     *
     * @param requestParam 已校验的购票请求
     * @param seatType 当前座位类型
     * @param candidateCarriages 当前候选车厢余票摘要
     * @return true 表示采用单通道，false 表示采用 Redis 乐观占位
     */
    public boolean shouldUseSingleChannel(PurchaseTicketReqDTO requestParam,
                                          Integer seatType,
                                          List<CarriageAvailabilityDTO> candidateCarriages) {
        // 兼容已有调用方；仅在探测或恢复状态缺少 reservation 时按单通道处理。
        return decide(requestParam, seatType, candidateCarriages, null).useSingleChannel();
    }

    /**
     * 评估共享状态机并返回本次请求固定的通道路由及统计样本类型。
     *
     * @param requestParam 已校验的购票请求
     * @param seatType 当前座位类型
     * @param candidateCarriages 当前候选车厢余票摘要
     * @param reservationId 当前购票的稳定 reservation 标识
     * @return 本次请求应使用的选座通道路由
     */
    public SeatSelectionRoute decide(PurchaseTicketReqDTO requestParam,
                                     Integer seatType,
                                     List<CarriageAvailabilityDTO> candidateCarriages,
                                     String reservationId) {
        // 先读取短时共享状态快照，避免同一库存维度的每个请求都执行两次窗口汇总与状态迁移脚本。
        String strategyKey = buildStrategyKey(requestParam, seatType);
        try {
            SeatSelectionStrategyState state = stateSnapshotCache.getIfPresent(strategyKey);
            if (state == null) {
                long nowMillis = System.currentTimeMillis();
                // 常态快速窗口控制快速降级；探测窗口只用于状态恢复，二者严格按 sampleType 隔离。
                SeatConflictStatistics normalStatistics = seatSelectionStrategyRedisStore.snapshot(
                        strategyKey, SeatSelectionWindow.NORMAL_FAST, nowMillis);
                SeatConflictStatistics probeStatistics = seatSelectionStrategyRedisStore.snapshot(
                        strategyKey, SeatSelectionWindow.PROBE, nowMillis);
                state = seatSelectionStrategyRedisStore.transition(strategyKey,
                        defaultStatistics(normalStatistics), defaultStatistics(probeStatistics),
                        availableSeatCount(candidateCarriages), buildStateConfig(), nowMillis);
                if (state == null) {
                    throw new IllegalStateException("Redis 座位策略状态读取为空");
                }
                stateSnapshotCache.put(strategyKey, state);
            }
            // 路由携带当前样本类型，后续 Lua 占位结果不能根据变化后的状态重新分类。
            return routeForState(state, reservationId);
        } catch (RuntimeException ex) {
            // 共享统计不可用不应阻断购票，改用既有本机窗口并记录固定低基数失败指标。
            recordRedisStatisticsFailure("state", ex);
            return localSeatSelectionStrategySelector.shouldUseSingleChannel(requestParam, seatType, candidateCarriages)
                    ? SeatSelectionRoute.singleChannel()
                    : SeatSelectionRoute.optimistic(SeatSelectionSampleType.NORMAL);
        }
    }

    /**
     * 以 reservation 为粒度上报一次 Redis Lua 临时占位结果，避免单个请求的重试放大冲突率。
     *
     * @param requestParam 已校验的购票请求
     * @param seatType 当前座位类型
     * @param reservationId 当前选座 reservation 标识
     * @param conflict Redis Lua 是否因座位已被其它请求占用而返回冲突
     */
    public void recordOptimisticSelectionResult(PurchaseTicketReqDTO requestParam,
                                                Integer seatType,
                                                String reservationId,
                                                boolean conflict) {
        // 兼容阶段五调用方；未携带路由上下文的常态乐观结果按 normal 样本处理。
        recordOptimisticSelectionResult(requestParam, seatType, reservationId, SeatSelectionSampleType.NORMAL, conflict);
    }

    /**
     * 按本次请求路由指定的样本类型上报一次 Redis Lua 临时占位结果。
     *
     * @param requestParam 已校验的购票请求
     * @param seatType 当前座位类型
     * @param reservationId 当前选座 reservation 标识
     * @param sampleType 本次乐观通道的常态或探测样本类型
     * @param conflict Redis Lua 是否因座位已被其它请求占用而返回冲突
     */
    public void recordOptimisticSelectionResult(PurchaseTicketReqDTO requestParam,
                                                Integer seatType,
                                                String reservationId,
                                                SeatSelectionSampleType sampleType,
                                                boolean conflict) {
        // 缺少 owner 标识时无法可靠控制贡献次数，跳过统计而不影响实际库存链路。
        if (StrUtil.isBlank(reservationId) || sampleType == null) {
            return;
        }
        String strategyKey = buildStrategyKey(requestParam, seatType);
        String contributionKey = strategyKey + ':' + reservationId;
        // 同一 reservation 在统计桶保留期内只贡献一次，保留多请求汇总后的真实热点信号。
        if (reservationContributionCache.asMap().putIfAbsent(contributionKey, Boolean.TRUE) != null) {
            return;
        }
        if (sampleType == SeatSelectionSampleType.NORMAL) {
            // 本机窗口只保留 normal 样本，避免探测流量污染 Redis 故障时的兼容降级判断。
            localSeatSelectionStrategySelector.recordOptimisticSelectionResult(requestParam, seatType, conflict);
        }
        try {
            // Lua 按当前路由的 normal 或 probe 类型原子累计样本，供全部 ticket-service 实例共同读取。
            seatSelectionStrategyRedisStore.record(
                    strategyKey, sampleType, reservationId, conflict, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            // 统计写入失败不应被上层误判为座位占位失败，实际选座继续由 Redis bitmap 与数据库保证。
            recordRedisStatisticsFailure("record", ex);
        }
    }

    /**
     * 计算当前候选车厢可售座位总数，用于共享统计样本不足时的安全兜底。
     *
     * @param candidateCarriages 当前候选车厢余票摘要
     * @return 候选车厢可售座位总数
     */
    private int availableSeatCount(List<CarriageAvailabilityDTO> candidateCarriages) {
        // 候选集合由上游余票查询生成；空集合按零处理，避免在边界情况下误走乐观通道。
        return candidateCarriages.stream().mapToInt(CarriageAvailabilityDTO::getSeatCount).sum();
    }

    /**
     * 把 Redis 缺失统计统一转换为空窗口，避免部分桶过期时中断策略评估。
     *
     * @param statistics Redis 返回的统计，可为空
     * @return 可参与状态机计算的非空统计
     */
    private SeatConflictStatistics defaultStatistics(SeatConflictStatistics statistics) {
        // 统计脚本正常会返回零值；该保护仅覆盖测试客户端或临界过期时的空返回。
        return statistics == null ? new SeatConflictStatistics(0L, 0L, 0L) : statistics;
    }

    /**
     * 根据服务配置生成 Lua 使用的状态机阈值，统一在服务端完成浮点转整数。
     *
     * @return Redis Lua 可直接比较的策略状态机配置
     */
    private SeatSelectionStrategyStateConfig buildStateConfig() {
        // 使用万分比传递冲突率，Lua 只做整数乘法比较，避免跨运行时浮点边界不一致。
        return new SeatSelectionStrategyStateConfig(stateEvaluationIntervalMillis, conflictWindowMinAttempts,
                toBasisPoints(conflictRateThreshold), toBasisPoints(recoveryConflictRateThreshold), lowStockThreshold,
                singleMinimumResidenceMillis, probePercentage, recoveryHealthyPeriods);
    }

    /**
     * 将百分比阈值转换为 Lua 使用的万分比整数。
     *
     * @param rate 范围为零到一的冲突率
     * @return 万分比整数
     */
    private int toBasisPoints(double rate) {
        // 配置值由服务端固定，四舍五入后可稳定表达百分之七十和百分之五十等阈值。
        return (int) Math.round(rate * 10_000D);
    }

    /**
     * 根据共享状态和 reservation 的稳定哈希产生本次请求的确定性路由。
     *
     * @param state Redis Lua 返回的共享状态
     * @param reservationId 当前购票 reservation 标识
     * @return 固定到当前请求生命周期的选座通道路由
     */
    private SeatSelectionRoute routeForState(SeatSelectionStrategyState state, String reservationId) {
        // 常态乐观流量使用 normal 样本；单通道不产生 Redis 临时占位样本。
        if (state.mode() == SeatSelectionStrategyMode.OPTIMISTIC) {
            return SeatSelectionRoute.optimistic(SeatSelectionSampleType.NORMAL);
        }
        if (state.mode() == SeatSelectionStrategyMode.SINGLE || StrUtil.isBlank(reservationId)) {
            return SeatSelectionRoute.singleChannel();
        }
        // PROBING 与 RECOVERING 均使用 reservationId 哈希稳定分流，避免同一请求重试时切换通道。
        int bucket = Math.floorMod(reservationId.hashCode(), 100);
        return bucket < state.optimisticPercentage()
                ? SeatSelectionRoute.optimistic(SeatSelectionSampleType.PROBE)
                : SeatSelectionRoute.singleChannel();
    }

    /**
     * 构造同一运行库存维度的共享策略键。
     *
     * @param requestParam 已校验的购票请求
     * @param seatType 当前座位类型
     * @return 包含始发日期的共享策略键
     */
    private String buildStrategyKey(PurchaseTicketReqDTO requestParam, Integer seatType) {
        // 复用阶段四定义的共享维度，确保统计桶和状态机读取完全相同的 Redis Cluster Hash Tag。
        return SeatSelectionStrategyKeyBuilder.build(requestParam, seatType);
    }

    /**
     * 记录 Redis 共享统计的基础设施失败，且保证监控异常不会影响购票请求。
     *
     * @param operation 失败的固定操作名称
     * @param ex Redis 或监控调用异常
     */
    private void recordRedisStatisticsFailure(String operation, RuntimeException ex) {
        // 读取错误频率受短时缓存限制，写入错误仅保留调试日志以避免 Redis 故障时刷屏。
        log.debug("Redis seat selection strategy statistics {} failed", operation, ex);
        try {
            // 标签使用固定阶段和原因，避免库存维度进入监控系统造成高基数。
            ticketPurchaseMetrics.recordFailure("strategy_statistics", "redis_error");
        } catch (RuntimeException metricsEx) {
            // 监控不可用不能掩盖 Redis 统计错误，更不能阻断实际购票链路。
            log.debug("Ticket purchase metrics record for strategy statistics failed", metricsEx);
        }
    }
}
