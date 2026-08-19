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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Timer;
import org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageAvailabilityDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PurchaseSeatContext;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PreparedSeatSelection;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.SelectSeatDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationSegmentBitmapUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.RemoteException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEAT_ALLOCATION_CURSOR;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_PRICE;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;



/**
 * 购票时列车座位选择器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class TrainSeatTypeSelector {

    private static final int MAX_CARRIAGE_SELECT_RETRY_TIMES = 3;
    private static final int DEFAULT_REDIS_BITMAP_SELECT_RETRY_TIMES = 12;
    private static final int MAX_SELECT_RETRY_TIMES = 256;
    private static final long RESOURCE_LOCK_WAIT_MILLIS = 30L;

    private final SeatService seatService;
    private final UserRemoteService userRemoteService;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final AbstractStrategyChoose abstractStrategyChoose;
    private final ThreadPoolExecutor selectSeatThreadPoolExecutor;
    private final DistributedCache distributedCache;
    private final TrainStationService trainStationService;
    private final RedissonClient redissonClient;
    private final ConfigurableEnvironment environment;
    private final RedisSeatBitmapService redisSeatBitmapService;
    private final TicketPurchaseMetrics ticketPurchaseMetrics;
    private final SeatSelectionStrategySharedSelector seatSelectionStrategySharedSelector;
    private final SingleChannelSeatSelectionLimiter singleChannelSeatSelectionLimiter;

    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;

    @Value("${ticket.seat.redis-bitmap.enabled:true}")
    private Boolean redisSeatBitmapEnabled;

    @Value("${ticket.seat.redis-bitmap-select-retry-times:12}")
    private Integer redisBitmapSelectRetryTimes;

    @Value("${ticket.seat.single-channel.max-carriage-attempts:3}")
    private int singleChannelMaxCarriageAttempts = 3;

    @Value("${ticket.seat.single-channel.max-select-millis:300}")
    private long singleChannelMaxSelectMillis = 300L;

    /**
     * 在锁座事务外批量加载乘车人权威快照和区间席别价格。
     *
     * @param requestParam 已通过基础责任链校验的购票请求
     * @return 仅供本次锁座流程读取的不可变上下文
     */
    public PurchaseSeatContext preparePurchaseContext(PurchaseTicketReqDTO requestParam) {
        // 一次远程请求读取全部乘车人，禁止锁座后再等待用户服务返回。
        List<String> passengerIds = requestParam.getPassengers().stream()
                .map(PurchaseTicketPassengerDetailDTO::getPassengerId)
                .distinct()
                .toList();
        Timer.Sample passengerTimer = ticketPurchaseMetrics.startStageTimer();
        Map<String, PassengerRespDTO> passengerById;
        try {
            Result<List<PassengerRespDTO>> passengerResult = userRemoteService.listPassengerQueryByIds(
                    UserContext.getUsername(), passengerIds);
            if (!passengerResult.isSuccess() || CollUtil.isEmpty(passengerResult.getData())) {
                throw new RemoteException("用户服务远程调用查询乘车人相关信息错误");
            }
            passengerById = passengerResult.getData().stream()
                    .collect(Collectors.toMap(PassengerRespDTO::getId, each -> each, (left, right) -> left));
            if (passengerIds.stream().anyMatch(each -> !passengerById.containsKey(each))) {
                throw new ServiceException("乘车人不存在或不属于当前用户");
            }
            ticketPurchaseMetrics.recordStage(passengerTimer, "passenger_remote", "success");
        } catch (Throwable ex) {
            ticketPurchaseMetrics.recordStage(passengerTimer, "passenger_remote", "failed");
            log.error("用户服务远程调用查询乘车人相关信息错误，当前用户：{}，请求参数：{}",
                    UserContext.getUsername(), passengerIds, ex);
            throw ex;
        }

        // 复用车票查询使用的区间价格缓存，缓存缺失时也只执行一次批量查询。
        Timer.Sample priceTimer = ticketPurchaseMetrics.startStageTimer();
        Map<Integer, Integer> priceBySeatType;
        try {
            String priceCacheKey = String.format(TRAIN_STATION_PRICE, requestParam.getTrainId(),
                    requestParam.getDeparture(), requestParam.getArrival());
            String priceJson = distributedCache.safeGet(
                    priceCacheKey,
                    String.class,
                    () -> {
                        LambdaQueryWrapper<TrainStationPriceDO> queryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                                .eq(TrainStationPriceDO::getTrainId, requestParam.getTrainId())
                                .eq(TrainStationPriceDO::getDeparture, requestParam.getDeparture())
                                .eq(TrainStationPriceDO::getArrival, requestParam.getArrival());
                        return JSON.toJSONString(trainStationPriceMapper.selectList(queryWrapper));
                    },
                    ADVANCE_TICKET_DAY,
                    TimeUnit.DAYS);
            List<TrainStationPriceDO> prices = JSON.parseArray(priceJson, TrainStationPriceDO.class);
            priceBySeatType = prices.stream().collect(Collectors.toMap(
                    TrainStationPriceDO::getSeatType,
                    TrainStationPriceDO::getPrice,
                    (left, right) -> left,
                    HashMap::new));
            Set<Integer> requestedSeatTypes = requestParam.getPassengers().stream()
                    .map(PurchaseTicketPassengerDetailDTO::getSeatType)
                    .collect(Collectors.toSet());
            if (requestedSeatTypes.stream().anyMatch(each -> !priceBySeatType.containsKey(each))) {
                throw new ServiceException("当前区间缺少所选席别价格");
            }
            ticketPurchaseMetrics.recordStage(priceTimer, "price_query", "success");
        } catch (Throwable ex) {
            ticketPurchaseMetrics.recordStage(priceTimer, "price_query", "failed");
            throw ex;
        }
        return new PurchaseSeatContext(Map.copyOf(passengerById), Map.copyOf(priceBySeatType));
    }

    /**
     * 为一次购票请求选择座位，并把同一 reservationId 写入 Redis 临时占位 owner。
     *
     * @param trainType 列车类型
     * @param requestParam 已校验的购票请求
     * @param reservationId 服务端生成且不可复用的座位占用标识
     * @param purchaseContext 锁座前加载完成的乘车人和价格快照
     * @return 已锁定并补齐乘车人和价格信息的座位明细
     */
    public List<TrainPurchaseTicketRespDTO> select(Integer trainType, PurchaseTicketReqDTO requestParam,
                                                    String reservationId, PurchaseSeatContext purchaseContext) {
        List<PurchaseTicketPassengerDetailDTO> passengerDetails = requestParam.getPassengers();
        // 按照座位类型分组
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = passengerDetails.stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        List<TrainPurchaseTicketRespDTO> actualResult = Collections.synchronizedList(new ArrayList<>(passengerDetails.size()));
        List<TrainPurchaseTicketRespDTO> redisHeldTickets = Collections.synchronizedList(new ArrayList<>(passengerDetails.size()));
        try {
            Timer.Sample inventoryReadyTimer = ticketPurchaseMetrics.startStageTimer();
            try {
                // 购票热路径只校验预生成结果，不再同步复制整列车的日期库存。
                seatService.validateServiceDateInventoryReady(requestParam.getTrainId(), requestParam.getServiceDate());
                ticketPurchaseMetrics.recordStage(inventoryReadyTimer, "inventory_ready", "success");
            } catch (Throwable ex) {
                ticketPurchaseMetrics.recordStage(inventoryReadyTimer, "inventory_ready", "failed");
                throw ex;
            }
            if (seatTypeMap.size() > 1) {
                // 不同席别仍由专用线程池并行选择，但当前请求线程顺序汇总，避免额外占用 ForkJoin 公共线程池。
                List<Future<List<TrainPurchaseTicketRespDTO>>> futureResults = new ArrayList<>(seatTypeMap.size());
                seatTypeMap.forEach((seatType, passengerSeatDetails) -> futureResults.add(selectSeatThreadPoolExecutor
                        .submit(() -> distributeSeats(trainType, seatType, requestParam, passengerSeatDetails, reservationId, redisHeldTickets))));
                Exception selectionFailure = null;
                for (Future<List<TrainPurchaseTicketRespDTO>> future : futureResults) {
                    try {
                        actualResult.addAll(future.get());
                    } catch (Exception ex) {
                        // 仍需等待其余席别任务结束，确保它们写入的 owner 全部进入统一补偿范围。
                        selectionFailure = ex;
                    }
                }
                if (selectionFailure != null) {
                    throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
                }
            } else {
                seatTypeMap.forEach((seatType, passengerSeatDetails) -> actualResult.addAll(
                        distributeSeats(trainType, seatType, requestParam, passengerSeatDetails, reservationId, redisHeldTickets)));
            }
            // 校验选座结果是否完整
            if (CollUtil.isEmpty(actualResult) || !Objects.equals(actualResult.size(), passengerDetails.size())) {
                throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
            }
            // 只做内存映射，锁座事务中不再访问用户服务或票价表。
            enrichFromPreparedContext(actualResult, purchaseContext);
            // 选座完整返回后，Redis owner 的补偿责任转移至购票主流程。
            redisHeldTickets.clear();
            return actualResult;
        } catch (Throwable ex) {
            // 在结果返回给购票主流程前，选座器必须回收本次已获得的全部 Redis owner。
            releaseRedisSeatHoldsAfterSelectionFailure(requestParam, redisHeldTickets, "seat_selection");
            throw ex;
        }
    }

    /**
     * 根据余票和冲突状态选择乐观 Redis 通道或车厢区间锁单通道。
     *
     * @param trainType 列车类型
     * @param seatType 座位类型
     * @param requestParam 购票请求
     * @param passengerSeatDetails 当前座位类型的乘客
     * @param reservationId 当前购票的座位占用标识
     * @param redisHeldTickets 当前请求已经获得 Redis owner 的座位
     * @return 已锁定座位
     */
    private List<TrainPurchaseTicketRespDTO> distributeSeats(Integer trainType, Integer seatType, PurchaseTicketReqDTO requestParam,
                                                              List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails,
                                                              String reservationId,
                                                              List<TrainPurchaseTicketRespDTO> redisHeldTickets) {
        Timer.Sample candidateTimer = ticketPurchaseMetrics.startStageTimer();
        List<CarriageAvailabilityDTO> candidateCarriages;
        try {
            // 候选车厢阶段包含运行库存摘要读取和 Redis 车厢摘要访问。
            candidateCarriages = seatService.listCandidateCarriages(
                    requestParam.getTrainId(), requestParam.getServiceDate(), seatType,
                    requestParam.getDeparture(), requestParam.getArrival(), passengerSeatDetails.size());
            if (CollUtil.isEmpty(candidateCarriages)) {
                throw new ServiceException("站点余票不足或座位资源冲突，请稍后重试");
            }
            ticketPurchaseMetrics.recordStage(candidateTimer, "candidate_carriage", "success");
        } catch (Throwable ex) {
            ticketPurchaseMetrics.recordStage(candidateTimer, "candidate_carriage", "failed");
            throw ex;
        }

        Timer.Sample strategyTimer = ticketPurchaseMetrics.startStageTimer();
        SeatSelectionRoute selectionRoute;
        try {
            // 路由结果固定到当前 reservation，后续 Redis 占位结果必须沿用同一 normal/probe 样本类型。
            selectionRoute = seatSelectionStrategySharedSelector.decide(
                    requestParam, seatType, candidateCarriages, reservationId);
            ticketPurchaseMetrics.recordStage(strategyTimer, "strategy_decide", "success");
        } catch (Throwable ex) {
            ticketPurchaseMetrics.recordStage(strategyTimer, "strategy_decide", "failed");
            throw ex;
        }

        Timer.Sample seatAllocateTimer = ticketPurchaseMetrics.startStageTimer();
        String seatAllocateResult = "failed";
        try {
            if (selectionRoute.useSingleChannel()) {
                // 低余票或冲突热点时直接串行化候选车厢，减少乐观占位失败后的重复扫描。
                ticketPurchaseMetrics.recordSelectionStrategy("single_channel");
                List<TrainPurchaseTicketRespDTO> selectedTickets = distributeSeatsBySingleChannel(
                        trainType, seatType, requestParam, passengerSeatDetails, reservationId, candidateCarriages);
                seatAllocateResult = "success";
                return selectedTickets;
            }
            if (Boolean.TRUE.equals(redisSeatBitmapEnabled)) {
                List<TrainPurchaseTicketRespDTO> currentSeatTypeHeldTickets = new ArrayList<>();
                try {
                    // 常态或探测路由均使用 Redis 原子临时占位，样本类型由当前路由显式传递。
                    ticketPurchaseMetrics.recordSelectionStrategy("optimistic");
                    List<TrainPurchaseTicketRespDTO> selectedTickets = distributeSeatsByRedisBitmap(
                            trainType, seatType, requestParam, passengerSeatDetails, reservationId,
                            candidateCarriages, redisHeldTickets, currentSeatTypeHeldTickets, selectionRoute.sampleType(), true);
                    seatAllocateResult = "success";
                    return selectedTickets;
                } catch (ServiceException ex) {
                    // Redis Lua 的每次直接结果已在选座循环内记录，不能用最终业务异常重复计数。
                    throw ex;
                } catch (Throwable ex) {
                    if (CollUtil.isNotEmpty(currentSeatTypeHeldTickets)) {
                        // Redis owner 已经写入时，后续数据库或指标异常不能伪装成 Redis 不可用后继续选座。
                        throw ex;
                    }
                    log.warn("Redis bitmap seat selection unavailable, fallback to DB bitmap. trainId={}, seatType={}",
                            requestParam.getTrainId(), seatType, ex);
                }
            }
            // Redis 基础设施不可用时沿用数据库区间锁回退，不把基础设施故障计为座位竞争。
            ticketPurchaseMetrics.recordSelectionStrategy("single_channel");
            List<TrainPurchaseTicketRespDTO> selectedTickets = distributeSeatsBySingleChannel(
                    trainType, seatType, requestParam, passengerSeatDetails, reservationId, candidateCarriages);
            seatAllocateResult = "success";
            return selectedTickets;
        } finally {
            ticketPurchaseMetrics.recordStage(seatAllocateTimer, "seat_allocate", seatAllocateResult);
        }
    }

    /**
     * 在数据库事务外完成乐观 Redis 临时占位，缩短数据库连接与行锁的持有时间。
     *
     * <p>仅常态 Redis 路径会返回已占位座位；低余票单通道和 Redis 不可用场景保留事务内既有逻辑，
     * 避免在未持有区间锁时改变热点车厢的竞争语义。</p>
     *
     * @param trainType 列车类型
     * @param requestParam 已完成基础校验的购票请求
     * @param reservationId 当前请求唯一的座位占用标识
     * @param purchaseContext 已准备的乘车人与票价快照
     * @return 乐观占位成功时返回座位明细，否则返回事务内选座回退标记
     */
    public PreparedSeatSelection prepareOptimisticRedisSeatSelection(Integer trainType,
                                                                       PurchaseTicketReqDTO requestParam,
                                                                       String reservationId,
                                                                       PurchaseSeatContext purchaseContext) {
        // Redis 位图关闭时不能伪造事务外占位，仍由原有数据库区间锁路径保证正确性。
        if (!Boolean.TRUE.equals(redisSeatBitmapEnabled)) {
            return PreparedSeatSelection.fallbackToTransactionalSelection();
        }
        // 库存日期检查只读预生成状态，不应占用后续数据库事务连接。
        seatService.validateServiceDateInventoryReady(requestParam.getTrainId(), requestParam.getServiceDate());
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        List<TrainPurchaseTicketRespDTO> selectedTickets = new ArrayList<>(requestParam.getPassengers().size());
        List<TrainPurchaseTicketRespDTO> redisHeldTickets = new ArrayList<>(requestParam.getPassengers().size());
        try {
            for (Map.Entry<Integer, List<PurchaseTicketPassengerDetailDTO>> entry : seatTypeMap.entrySet()) {
                Integer seatType = entry.getKey();
                List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails = entry.getValue();
                // 读取候选车厢摘要并决定本席别是否适合乐观 Redis 路径。
                List<CarriageAvailabilityDTO> candidateCarriages = seatService.listCandidateCarriages(
                        requestParam.getTrainId(), requestParam.getServiceDate(), seatType,
                        requestParam.getDeparture(), requestParam.getArrival(), passengerSeatDetails.size());
                if (CollUtil.isEmpty(candidateCarriages)) {
                    throw new ServiceException("站点余票不足或座位资源冲突，请稍后重试");
                }
                SeatSelectionRoute selectionRoute = seatSelectionStrategySharedSelector.decide(
                        requestParam, seatType, candidateCarriages, reservationId);
                if (selectionRoute.useSingleChannel()) {
                    // 已取得的 Redis owner 必须在回退前释放，防止事务内重选时与自身冲突。
                    releaseRedisSeatHoldsAfterSelectionFailure(requestParam, redisHeldTickets, "transactional_fallback");
                    return PreparedSeatSelection.fallbackToTransactionalSelection();
                }
                List<TrainPurchaseTicketRespDTO> currentSeatTypeHeldTickets = new ArrayList<>();
                ticketPurchaseMetrics.recordSelectionStrategy("optimistic");
                selectedTickets.addAll(distributeSeatsByRedisBitmap(
                        trainType, seatType, requestParam, passengerSeatDetails, reservationId,
                        candidateCarriages, redisHeldTickets, currentSeatTypeHeldTickets, selectionRoute.sampleType(), false));
            }
            if (selectedTickets.size() != requestParam.getPassengers().size()) {
                throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
            }
            // 事务外仅补齐不可变快照中的字段，不会写入任何数据库业务状态。
            enrichFromPreparedContext(selectedTickets, purchaseContext);
            return new PreparedSeatSelection(selectedTickets, true);
        } catch (Throwable ex) {
            // 事务尚未开始时失败，只需按 owner 条件回收本请求已持有的 Redis 座位。
            releaseRedisSeatHoldsAfterSelectionFailure(requestParam, redisHeldTickets, "optimistic_prepare");
            throw ex;
        }
    }

    /**
     * 在短本地事务中确认 Redis 已占位座位，并同步更新余票摘要。
     *
     * @param requestParam 当前购票请求
     * @param selectedTickets 已由当前 reservation 通过 Redis 占位的座位
     */
    public void confirmOptimisticRedisSeatSelection(PurchaseTicketReqDTO requestParam,
                                                     List<TrainPurchaseTicketRespDTO> selectedTickets) {
        Timer.Sample databaseConfirmTimer = ticketPurchaseMetrics.startStageTimer();
        try {
            // 数据库批量 CAS 与车票、reservation/Outbox 位于同一事务，提交前不会暴露半成品锁座。
            if (!seatService.tryLockSeat(requestParam.getTrainId(), requestParam.getServiceDate(),
                    requestParam.getDeparture(), requestParam.getArrival(), selectedTickets)) {
                throw new ServiceException("座位资源冲突，请稍后重试");
            }
            // 数据库确认成功后再更新各席别、各车厢的 Redis 余票摘要。
            Map<Integer, List<TrainPurchaseTicketRespDTO>> ticketsBySeatType = selectedTickets.stream()
                    .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getSeatType));
            ticketsBySeatType.forEach((seatType, sameTypeTickets) -> {
                decrementRemainingTicketAfterLock(requestParam, seatType, sameTypeTickets.size());
                sameTypeTickets.stream()
                        .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getCarriageNumber))
                        .forEach((carriageNumber, sameCarriageTickets) -> seatService.adjustCarriageRemainingSummary(
                                requestParam.getTrainId(), requestParam.getServiceDate(), requestParam.getDeparture(),
                                requestParam.getArrival(), seatType, carriageNumber, -sameCarriageTickets.size()));
            });
            ticketPurchaseMetrics.recordStage(databaseConfirmTimer, "database_confirm", "success");
        } catch (Throwable ex) {
            ticketPurchaseMetrics.recordStage(databaseConfirmTimer, "database_confirm", "failed");
            ticketPurchaseMetrics.recordFailure("database_confirm", "database_error");
            throw ex;
        }
    }

    private String buildCarriageSeatKey(TrainPurchaseTicketRespDTO ticket) {
        return ticket.getCarriageNumber() + "#" + ticket.getSeatNumber();
    }

    /**
     * 在进入区间锁选座前获取本机并发许可，避免热点单通道占满业务请求线程。
     *
     * @param trainType 列车类型
     * @param seatType 座位类型
     * @param requestParam 购票请求
     * @param passengerSeatDetails 当前座位类型的乘客
     * @param reservationId 当前购票的稳定座位占用标识
     * @param candidateCarriages 当前候选车厢余票摘要
     * @return 已锁定座位
     */
    private List<TrainPurchaseTicketRespDTO> distributeSeatsBySingleChannel(Integer trainType,
                                                                              Integer seatType,
                                                                              PurchaseTicketReqDTO requestParam,
                                                                              List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails,
                                                                              String reservationId,
                                                                              List<CarriageAvailabilityDTO> candidateCarriages) {
        // 单通道达到容量时立即失败，让网关或调用方控制重试节奏而不是堆积业务线程。
        if (!singleChannelSeatSelectionLimiter.tryAcquire()) {
            ticketPurchaseMetrics.recordFailure("single_channel", "concurrency_limit");
            throw new ServiceException("当前车次购票请求较多，请稍后重试");
        }
        try {
            // 许可只覆盖区间锁路径，乐观 Redis bitmap 路径不受该低余票保护阈值限制。
            return distributeSeatsByResourceLocks(trainType, seatType, requestParam, passengerSeatDetails,
                    reservationId, candidateCarriages);
        } finally {
            // 任意数据库或选座异常都必须归还许可，避免本机单通道永久拒绝后续请求。
            singleChannelSeatSelectionLimiter.release();
        }
    }

    /**
     * 在选座结果交给购票主流程前，按 owner 条件回收本次已经取得的 Redis 临时占位。
     *
     * @param requestParam 当前购票请求
     * @param redisHeldTickets 当前请求已成功写入 Redis owner 的座位
     * @param sourceStage 触发补偿的选座阶段
     */
    private void releaseRedisSeatHoldsAfterSelectionFailure(PurchaseTicketReqDTO requestParam,
                                                            List<TrainPurchaseTicketRespDTO> redisHeldTickets,
                                                            String sourceStage) {
        if (CollUtil.isEmpty(redisHeldTickets)) {
            return;
        }
        List<TrainPurchaseTicketRespDTO> heldTickets;
        synchronized (redisHeldTickets) {
            // 复制当前快照，避免多席别任务仍在结束时并发修改集合导致释放范围不完整。
            heldTickets = new ArrayList<>(redisHeldTickets);
        }
        Timer.Sample compensationTimer = startRedisCompensationTimer();
        try {
            // 座位明细携带 reservationId，释放 Lua 会校验 owner，重复补偿不会误释放新 owner。
            redisSeatBitmapService.releaseHeld(requestParam.getTrainId(), requestParam.getServiceDate(),
                    requestParam.getDeparture(), requestParam.getArrival(), heldTickets);
            recordRedisCompensationMetrics(compensationTimer, "success", sourceStage);
        } catch (Throwable releaseEx) {
            // 补偿失败不能覆盖原始选座异常，记录后交由后续对账与恢复处理。
            recordRedisCompensationMetrics(compensationTimer, "failed", sourceStage);
            log.error("ticket_redis_compensation_failed sourceStage={}, trainId={}, reservationId={}",
                    sourceStage, requestParam.getTrainId(), heldTickets.stream()
                            .map(TrainPurchaseTicketRespDTO::getRedisSeatHoldId)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null), releaseEx);
        }
    }

    /**
     * 创建 Redis 补偿阶段计时器，指标组件不可用时不影响座位释放。
     *
     * @return 可用的计时器；指标初始化失败时返回 null
     */
    private Timer.Sample startRedisCompensationTimer() {
        try {
            // 补偿的正确性优先于可观测性，指标初始化失败只记录告警。
            return ticketPurchaseMetrics.startStageTimer();
        } catch (Throwable metricsEx) {
            log.warn("ticket_redis_compensation_metrics_start_failed", metricsEx);
            return null;
        }
    }

    /**
     * 尽力记录 Redis 补偿结果，任何指标异常都不能覆盖原始选座或释放异常。
     *
     * @param compensationTimer 补偿阶段计时器，可为空
     * @param result 补偿结果
     * @param sourceStage 触发补偿的选座阶段
     */
    private void recordRedisCompensationMetrics(Timer.Sample compensationTimer, String result, String sourceStage) {
        try {
            // 只有成功创建计时器时才写耗时，失败路径仍记录固定维度的失败计数。
            if (compensationTimer != null) {
                ticketPurchaseMetrics.recordStage(compensationTimer, "redis_compensation", result);
            }
            if (StrUtil.equals(result, "failed")) {
                ticketPurchaseMetrics.recordFailure("redis_compensation", sourceStage);
            }
        } catch (Throwable metricsEx) {
            log.warn("ticket_redis_compensation_metrics_record_failed result={}, sourceStage={}", result, sourceStage, metricsEx);
        }
    }

    private void decrementRemainingTicketAfterLock(PurchaseTicketReqDTO requestParam, Integer seatType, int count) {
        if (StrUtil.equals(ticketAvailabilityCacheUpdateType, "binlog")) {
            return;
        }
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        routeDTOList.forEach(each -> {
            String keySuffix = StrUtil.join("_", requestParam.getTrainId(), ServiceDateKeyUtil.format(requestParam.getServiceDate()),
                    each.getStartStation(), each.getEndStation());
            stringRedisTemplate.opsForHash().increment(TRAIN_STATION_REMAINING_TICKET + keySuffix, String.valueOf(seatType), -count);
        });
    }

    /**
     * 使用 Redis 位图临时占位后再写入数据库座位位图。
     *
     * @param trainType 列车类型
     * @param seatType 座位类型
     * @param requestParam 购票请求
     * @param passengerSeatDetails 当前座位类型的乘客
     * @param reservationId 当前购票的座位占用标识
     * @param candidateCarriages 已按余票摘要排序的候选车厢
     * @param sampleType 当前乐观占位结果应写入的常态或探测样本类型
     * @param confirmDatabaseImmediately 是否在当前调用内确认数据库座位占用
     * @return 已锁定座位
     */
    private List<TrainPurchaseTicketRespDTO> distributeSeatsByRedisBitmap(Integer trainType,
                                                                          Integer seatType,
                                                                          PurchaseTicketReqDTO requestParam,
                                                                           List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails,
                                                                           String reservationId,
                                                                           List<CarriageAvailabilityDTO> candidateCarriages,
                                                                          List<TrainPurchaseTicketRespDTO> redisHeldTickets,
                                                                          List<TrainPurchaseTicketRespDTO> currentSeatTypeHeldTickets,
                                                                          SeatSelectionSampleType sampleType,
                                                                          boolean confirmDatabaseImmediately) {
        String strategyKey = VehicleTypeEnum.findNameByCode(trainType) + VehicleSeatTypeEnum.findNameByCode(seatType);
        long scanSeed = buildSeatScanSeed(requestParam, seatType, passengerSeatDetails);
        int carriageAttempt = 0;
        for (CarriageAvailabilityDTO eachCarriage : candidateCarriages) {
            String carriageNumber = eachCarriage.getCarriageNumber();
            Set<String> excludedSeatKeys = new LinkedHashSet<>();
            int maxRetryTimes = Optional.ofNullable(redisBitmapSelectRetryTimes).orElse(DEFAULT_REDIS_BITMAP_SELECT_RETRY_TIMES);
            for (int retry = 0; retry < maxRetryTimes; retry++) {
                Integer seatScanOffset = allocateSeatScanOffset(
                        requestParam,
                        seatType,
                        carriageNumber,
                        passengerSeatDetails.size(),
                        scanSeed,
                        carriageAttempt,
                        retry
                );
                SelectSeatDTO selectSeatDTO = SelectSeatDTO.builder()
                        .seatType(seatType)
                        .passengerSeatDetails(passengerSeatDetails)
                        .requestParam(requestParam)
                        .excludeSeatNumbers(new ArrayList<>(excludedSeatKeys))
                        .preferredCarriageNumber(carriageNumber)
                        .seatScanOffset(seatScanOffset)
                        .build();
                List<TrainPurchaseTicketRespDTO> selectedSeats = abstractStrategyChoose.chooseAndExecuteResp(strategyKey, selectSeatDTO);
                if (CollUtil.isEmpty(selectedSeats)) {
                    break;
                }
                Timer.Sample redisHoldTimer = ticketPurchaseMetrics.startStageTimer();
                String holdId;
                try {
                    // Redis Lua 负责原子检查和临时占位，冲突时返回空以便在当前请求内更换候选座位。
                    holdId = redisSeatBitmapService.tryHold(
                            requestParam.getTrainId(),
                            requestParam.getServiceDate(),
                            requestParam.getDeparture(),
                            requestParam.getArrival(),
                            seatType,
                            selectedSeats,
                            reservationId
                    );
                    if (StrUtil.isNotBlank(holdId)) {
                        // 先登记 owner，再记录指标或访问数据库，保证之后任意异常都具备精确释放凭证。
                        redisHeldTickets.addAll(selectedSeats);
                        currentSeatTypeHeldTickets.addAll(selectedSeats);
                    }
                    ticketPurchaseMetrics.recordStage(redisHoldTimer, "redis_hold", StrUtil.isBlank(holdId) ? "conflict" : "success");
                    // 策略统计只以 Lua 临时占位结果为样本，不混入后续数据库确认或订单落库失败。
                    seatSelectionStrategySharedSelector.recordOptimisticSelectionResult(
                            requestParam, seatType, reservationId, sampleType, StrUtil.isBlank(holdId));
                } catch (Throwable ex) {
                    // Redis 不可用时保留既有回退逻辑，同时记录基础设施失败。
                    ticketPurchaseMetrics.recordStage(redisHoldTimer, "redis_hold", "failed");
                    ticketPurchaseMetrics.recordFailure("redis_hold", "redis_error");
                    throw ex;
                }
                if (StrUtil.isBlank(holdId)) {
                    // 竞争冲突不等同于系统故障，单独统计以驱动后续策略选择器阈值。
                    ticketPurchaseMetrics.recordFailure("redis_hold", "seat_conflict");
                    selectedSeats.stream().map(this::buildCarriageSeatKey).forEach(excludedSeatKeys::add);
                    continue;
                }
                if (!confirmDatabaseImmediately) {
                    // 正常路径的数据库确认由短事务统一执行，Redis owner 已可阻止其他实例抢占同一座位。
                    return selectedSeats;
                }
                Timer.Sample databaseConfirmTimer = ticketPurchaseMetrics.startStageTimer();
                boolean databaseLocked;
                try {
                    // Redis 临时占位后仍由数据库条件锁定作为最终正确性确认。
                    databaseLocked = seatService.tryLockSeat(requestParam.getTrainId(), requestParam.getServiceDate(),
                            requestParam.getDeparture(), requestParam.getArrival(), selectedSeats);
                    ticketPurchaseMetrics.recordStage(databaseConfirmTimer, "database_confirm", databaseLocked ? "success" : "conflict");
                } catch (Throwable ex) {
                    // 数据库执行异常需要释放当前 Redis 临时占位，再交由外层回退或失败处理。
                    ticketPurchaseMetrics.recordStage(databaseConfirmTimer, "database_confirm", "failed");
                    ticketPurchaseMetrics.recordFailure("database_confirm", "database_error");
                    throw ex;
                }
                if (databaseLocked) {
                    decrementRemainingTicketAfterLock(requestParam, seatType, selectedSeats.size());
                    seatService.adjustCarriageRemainingSummary(
                            requestParam.getTrainId(),
                            requestParam.getServiceDate(),
                            requestParam.getDeparture(),
                            requestParam.getArrival(),
                            seatType,
                            carriageNumber,
                            -selectedSeats.size()
                    );
                    return selectedSeats;
                }
                redisSeatBitmapService.releaseByHoldId(
                        requestParam.getTrainId(),
                        requestParam.getServiceDate(),
                        requestParam.getDeparture(),
                        requestParam.getArrival(),
                        seatType,
                        selectedSeats
                );
                int beforeExcludeSize = excludedSeatKeys.size();
                selectedSeats.stream().map(this::buildCarriageSeatKey).forEach(excludedSeatKeys::add);
                if (excludedSeatKeys.size() == beforeExcludeSize) {
                    break;
                }
            }
            carriageAttempt++;
        }
        throw new ServiceException("座位资源冲突，请稍后重试");
    }

    /**
     * 在候选车厢上按区间锁顺序确认座位，用于低余票或热点冲突场景。
     *
     * @param trainType 列车类型
     * @param seatType 座位类型
     * @param requestParam 购票请求
     * @param passengerSeatDetails 当前座位类型的乘客
     * @param reservationId 当前购票的稳定座位占用标识
     * @param candidateCarriages 已按余票摘要排序的候选车厢
     * @return 已锁定座位
     */
    private List<TrainPurchaseTicketRespDTO> distributeSeatsByResourceLocks(Integer trainType,
                                                                            Integer seatType,
                                                                            PurchaseTicketReqDTO requestParam,
                                                                            List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails,
                                                                            String reservationId,
                                                                            List<CarriageAvailabilityDTO> candidateCarriages) {
        String strategyKey =
                VehicleTypeEnum.findNameByCode(trainType) + VehicleSeatTypeEnum.findNameByCode(seatType);
        // 获取区间路段索引列表    b-d会返回 1，2，3
        List<Integer> segmentIndexes = buildSegmentIndexes(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        // 根据 reservation 稳定打散候选起点，避免所有热点请求从同一车厢开始竞争。
        List<CarriageAvailabilityDTO> orderedCarriages = orderCandidateCarriages(candidateCarriages, reservationId);
        // 生成一个座位扫描种子，用于在每个车厢内随机扫描座位，避免冲突和死锁。
        long scanSeed = buildSeatScanSeed(requestParam, seatType, passengerSeatDetails);
        // 统一截止时间限制每个请求的锁等待、数据库确认和重试总耗时。
        long selectionDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(singleChannelMaxSelectMillis);
        int maxCarriageAttempts = Math.min(orderedCarriages.size(), Math.max(1, singleChannelMaxCarriageAttempts));
        // 初始化车厢尝试次数。
        int carriageAttempt = 0;
        // 只尝试有限数量的稳定分散车厢，超时或锁失败时快速把压力交回上游。
        for (int carriageIndex = 0; carriageIndex < maxCarriageAttempts; carriageIndex++) {
            if (System.nanoTime() >= selectionDeadlineNanos) {
                break;
            }
            CarriageAvailabilityDTO eachCarriage = orderedCarriages.get(carriageIndex);
            // 取出当前车厢号
            String carriageNumber = eachCarriage.getCarriageNumber();
            // 尝试获取“车厢 + 区间段”锁集合
            List<RLock> segmentLocks = tryAcquireCarriageSegmentLocks(requestParam.getTrainId(), requestParam.getServiceDate(),
                    seatType, carriageNumber, segmentIndexes, selectionDeadlineNanos);
            // 拿不到锁就换车厢重试
            if (CollUtil.isEmpty(segmentLocks)) {
                carriageAttempt++;
                continue;
            }
            try {
                // 本车厢里已经尝试过但锁座失败的那些座位 key。
                Set<String> excludedSeatKeys = new LinkedHashSet<>();
                for (int retry = 0; retry < MAX_CARRIAGE_SELECT_RETRY_TIMES; retry++) {
                    if (System.nanoTime() >= selectionDeadlineNanos) {
                        break;
                    }
                    // 构造当前轮次的选座参数对象
                    SelectSeatDTO selectSeatDTO = SelectSeatDTO.builder()
                            .seatType(seatType)
                            .passengerSeatDetails(passengerSeatDetails)
                            .requestParam(requestParam)
                            // 把当前已经尝试失败的座位传进去，告诉选座策略：这些座位别再选了。
                            .excludeSeatNumbers(new ArrayList<>(excludedSeatKeys))
                            // 这次只在当前这个车厢里选，不要跑别的车厢。
                            .preferredCarriageNumber(carriageNumber)
                            .seatScanOffset(buildSeatScanOffset(scanSeed, carriageAttempt, retry))
                            .build();
                    // 调用选座策略实际选座
                    List<TrainPurchaseTicketRespDTO> selectedSeats = abstractStrategyChoose.chooseAndExecuteResp(strategyKey, selectSeatDTO);
                    if (CollUtil.isEmpty(selectedSeats)) {
                        break;
                    }
                    if (seatService.tryLockSeat(requestParam.getTrainId(), requestParam.getServiceDate(),
                            requestParam.getDeparture(), requestParam.getArrival(), selectedSeats)) {
                        // 这里使用canal加binlog日志跟新
                        decrementRemainingTicketAfterLock(requestParam, seatType, selectedSeats.size());
                        // 再扣减当前车厢的摘要余票
                        seatService.adjustCarriageRemainingSummary(
                                requestParam.getTrainId(),
                                requestParam.getServiceDate(),
                                requestParam.getDeparture(),
                                requestParam.getArrival(),
                                seatType,
                                carriageNumber,
                                -selectedSeats.size()
                        );
                        return selectedSeats;
                    }
                    int beforeExcludeSize = excludedSeatKeys.size();
                    selectedSeats.stream().map(this::buildCarriageSeatKey).forEach(excludedSeatKeys::add);
                    if (excludedSeatKeys.size() == beforeExcludeSize) {
                        break;
                    }
                }
            } finally {
                releaseSegmentLocks(segmentLocks);
            }
            carriageAttempt++;
        }
        throw new ServiceException("座位资源冲突，请稍后重试");
    }

    /**
     * 获取指定始发日期、车厢和区间段的资源锁，默认仅使用单把锁的短等待时间。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param seatType 席别类型
     * @param carriageNumber 待尝试车厢号
     * @param segmentIndexes 需要保护的区间段索引
     * @return 全部锁获取成功时返回锁集合；任一锁超时或失败时释放已获取锁并返回空集合
     */
    private List<RLock> tryAcquireCarriageSegmentLocks(String trainId, java.util.Date serviceDate, Integer seatType,
                                                        String carriageNumber, List<Integer> segmentIndexes) {
        // 兼容已有调用和锁参数测试；真实单通道请求使用带整体截止时间的重载方法。
        return tryAcquireCarriageSegmentLocks(trainId, serviceDate, seatType, carriageNumber, segmentIndexes, Long.MAX_VALUE);
    }

    /**
     * 在请求整体截止时间内获取指定车厢的全部区间锁，任一锁失败时逆序释放已获得锁。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param seatType 席别类型
     * @param carriageNumber 待尝试车厢号
     * @param segmentIndexes 需要保护的区间段索引
     * @param selectionDeadlineNanos 单通道选座整体截止时间
     * @return 全部锁获取成功时返回锁集合；超时或失败时返回空集合
     */
    private List<RLock> tryAcquireCarriageSegmentLocks(String trainId, java.util.Date serviceDate, Integer seatType,
                                                        String carriageNumber, List<Integer> segmentIndexes,
                                                        long selectionDeadlineNanos) {
        List<RLock> locks = new ArrayList<>(segmentIndexes.size());
        for (Integer segmentIndex : segmentIndexes.stream().distinct().sorted().toList()) {
            long remainingNanos = selectionDeadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                releaseSegmentLocks(locks);
                return Collections.emptyList();
            }
            String lockKey = environment.resolvePlaceholders(String.format(
                    RedisKeyConstant.LOCK_PURCHASE_TICKETS_RESOURCE_SEGMENT,
                    trainId,
                    ServiceDateKeyUtil.format(serviceDate),
                    seatType,
                    carriageNumber,
                    segmentIndex
            ));
            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = false;
            try {
                long waitMillis = Math.min(RESOURCE_LOCK_WAIT_MILLIS,
                        Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                // 不传显式租约以启用 Redisson 看门狗，避免慢数据库确认期间锁在八秒后提前失效。
                locked = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Throwable ex) {
                log.warn("获取区间资源锁失败, key={}", lockKey, ex);
            }
            if (!locked) {
                releaseSegmentLocks(locks);
                return Collections.emptyList();
            }
            locks.add(lock);
        }
        return locks;
    }

    private void releaseSegmentLocks(List<RLock> locks) {
        for (int i = locks.size() - 1; i >= 0; i--) {
            try {
                if (locks.get(i).isHeldByCurrentThread()) {
                    locks.get(i).unlock();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 按 reservationId 稳定旋转候选车厢，分散热点请求的首个锁竞争目标。
     *
     * @param candidateCarriages 原始候选车厢顺序
     * @param reservationId 当前购票的稳定座位占用标识
     * @return 不修改原集合的稳定旋转候选车厢顺序
     */
    private List<CarriageAvailabilityDTO> orderCandidateCarriages(List<CarriageAvailabilityDTO> candidateCarriages,
                                                                    String reservationId) {
        List<CarriageAvailabilityDTO> orderedCarriages = new ArrayList<>(candidateCarriages);
        if (orderedCarriages.size() < 2 || StrUtil.isBlank(reservationId)) {
            return orderedCarriages;
        }
        // 相同 reservation 重试始终从同一车厢开始，不同请求则在候选车厢之间均匀分散。
        int startIndex = Math.floorMod(reservationId.hashCode(), orderedCarriages.size());
        Collections.rotate(orderedCarriages, -startIndex);
        return orderedCarriages;
    }

    private List<Integer> buildSegmentIndexes(String trainId, String departure, String arrival) {
        List<String> stationNames = trainStationService.listTrainStationNameByTrainId(trainId);
        Map<String, Integer> stationIndexMap = StationSegmentBitmapUtil.buildStationIndexMap(stationNames);
        Integer departureIndex = stationIndexMap.get(departure);
        Integer arrivalIndex = stationIndexMap.get(arrival);
        if (departureIndex == null || arrivalIndex == null || departureIndex >= arrivalIndex) {
            throw new ServiceException("出发站或到达站不合法");
        }
        List<Integer> segmentIndexes = new ArrayList<>(arrivalIndex - departureIndex);
        for (int i = departureIndex; i < arrivalIndex; i++) {
            segmentIndexes.add(i);
        }
        return segmentIndexes;
    }

    private long buildSeatScanSeed(PurchaseTicketReqDTO requestParam, Integer seatType, List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails) {
        String passengerKey = passengerSeatDetails.stream()
                .map(PurchaseTicketPassengerDetailDTO::getPassengerId)
                .sorted()
                .collect(Collectors.joining(","));
        return (requestParam.getTrainId() + "|" + ServiceDateKeyUtil.format(requestParam.getServiceDate()) + "|"
                + requestParam.getDeparture() + "|" + requestParam.getArrival()
                + "|" + seatType + "|" + UserContext.getUserId() + "|" + passengerKey).hashCode() & 0x7fffffffL;
    }

    private Integer buildSeatScanOffset(long seed, int carriageAttempt, int retry) {
        long mixed = seed + carriageAttempt * 131L + retry * 17L;
        return (int) Math.floorMod(mixed, Integer.MAX_VALUE);
    }

    private Integer allocateSeatScanOffset(PurchaseTicketReqDTO requestParam, Integer seatType, String carriageNumber,
                                           int passengerCount, long scanSeed, int carriageAttempt, int retry) {
        String cursorKey = TRAIN_CARRIAGE_SEAT_ALLOCATION_CURSOR
                + StrUtil.join("_", requestParam.getTrainId(), ServiceDateKeyUtil.format(requestParam.getServiceDate()),
                seatType, carriageNumber, requestParam.getDeparture(), requestParam.getArrival());
        long step = Math.max(1, passengerCount) * 7L + retry * 13L + 1L;
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        Long cursor = stringRedisTemplate.opsForValue().increment(cursorKey, step);
        stringRedisTemplate.expire(cursorKey, 1, TimeUnit.DAYS);
        long mixed = scanSeed + Optional.ofNullable(cursor).orElse(0L) + carriageAttempt * 131L + retry * 17L;
        return (int) Math.floorMod(mixed, Integer.MAX_VALUE);
    }

    /**
     * 使用锁座前准备的快照补齐座位结果，不在数据库事务中执行远程调用或价格查询。
     *
     * @param actualResult 已锁定的座位结果
     * @param purchaseContext 本次请求的乘车人和价格快照
     */
    private void enrichFromPreparedContext(List<TrainPurchaseTicketRespDTO> actualResult,
                                           PurchaseSeatContext purchaseContext) {
        // 每个座位必须匹配当前用户的权威乘车人快照和所选席别价格。
        actualResult.forEach(each -> {
            PassengerRespDTO passenger = purchaseContext.passengerById().get(each.getPassengerId());
            Integer price = purchaseContext.priceBySeatType().get(each.getSeatType());
            if (passenger == null || price == null) {
                throw new ServiceException("购票上下文与选座结果不一致");
            }
            each.setIdCard(passenger.getIdCard());
            each.setPhone(passenger.getPhone());
            each.setUserType(passenger.getDiscountType());
            each.setIdType(passenger.getIdType());
            each.setRealName(passenger.getRealName());
            each.setAmount(price);
        });
    }
}
