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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.SelectSeatDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationSegmentBitmapUtil;
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

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEAT_ALLOCATION_CURSOR;
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
    private static final long RESOURCE_LOCK_LEASE_SECONDS = 8L;

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

    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;

    @Value("${ticket.seat.redis-bitmap.enabled:true}")
    private Boolean redisSeatBitmapEnabled;

    @Value("${ticket.seat.redis-bitmap-select-retry-times:12}")
    private Integer redisBitmapSelectRetryTimes;

    public List<TrainPurchaseTicketRespDTO> select(Integer trainType, PurchaseTicketReqDTO requestParam) {
        List<PurchaseTicketPassengerDetailDTO> passengerDetails = requestParam.getPassengers();
        // 按照座位类型分组
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = passengerDetails.stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        List<TrainPurchaseTicketRespDTO> actualResult = Collections.synchronizedList(new ArrayList<>(passengerDetails.size()));
        if (seatTypeMap.size() > 1) {
            // 这里并发执行
            List<Future<List<TrainPurchaseTicketRespDTO>>> futureResults = new ArrayList<>(seatTypeMap.size());
            seatTypeMap.forEach((seatType, passengerSeatDetails) -> futureResults.add(selectSeatThreadPoolExecutor
                    .submit(() -> distributeSeats(trainType, seatType, requestParam, passengerSeatDetails))));
            futureResults.parallelStream().forEach(future -> {
                try {
                    actualResult.addAll(future.get());
                } catch (Exception e) {
                    // 只要有一组数据不对就直接报错
                    throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
                }
            });
        } else {
            seatTypeMap.forEach((seatType, passengerSeatDetails) -> actualResult.addAll(distributeSeats(trainType, seatType, requestParam, passengerSeatDetails)));
        }
        // 校验选座结果是否完整
        if (CollUtil.isEmpty(actualResult) || !Objects.equals(actualResult.size(), passengerDetails.size())) {
            throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
        }
        enrichPassengerAndPrice(actualResult, requestParam);
        return actualResult;
    }

    private List<TrainPurchaseTicketRespDTO> distributeSeats(Integer trainType, Integer seatType, PurchaseTicketReqDTO requestParam, List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails) {
        if (Boolean.TRUE.equals(redisSeatBitmapEnabled)) {
            try {
                return distributeSeatsByRedisBitmap(trainType, seatType, requestParam, passengerSeatDetails);
            } catch (ServiceException ex) {
                throw ex;
            } catch (Throwable ex) {
                log.warn("Redis bitmap seat selection unavailable, fallback to DB bitmap. trainId={}, seatType={}",
                        requestParam.getTrainId(), seatType, ex);
            }
        }
        return distributeSeatsByResourceLocks(trainType, seatType, requestParam, passengerSeatDetails);
    }
    /*
        String buildStrategyKey = VehicleTypeEnum.findNameByCode(trainType)
                + VehicleSeatTypeEnum.findNameByCode(seatType);
        List<Integer> segmentIndexes = buildSegmentIndexes(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        Set<String> excludedSeatKeys = new LinkedHashSet<>();
        for (int retry = 0; retry < MAX_SELECT_RETRY_TIMES; retry++) {
            SelectSeatDTO selectSeatDTO = SelectSeatDTO.builder()
                    .seatType(seatType)
                    .passengerSeatDetails(passengerSeatDetails)
                    .requestParam(requestParam)
                    .excludeSeatNumbers(new ArrayList<>(excludedSeatKeys))
                    .build();
            List<TrainPurchaseTicketRespDTO> selectedSeats;
            try {
                selectedSeats = abstractStrategyChoose.chooseAndExecuteResp(buildStrategyKey, selectSeatDTO);
            } catch (ServiceException ex) {
                throw new ServiceException("当前车次列车类型暂未适配，请购买G35或G39车次");
            }
            if (CollUtil.isEmpty(selectedSeats)) {
                break;
            }
            if (seatService.tryLockSeat(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival(), selectedSeats)) {
                decrementRemainingTicketAfterLock(requestParam, seatType, selectedSeats.size());
                return selectedSeats;
            }
            int beforeExcludeSize = excludedSeatKeys.size();
            selectedSeats.stream().map(this::buildCarriageSeatKey).forEach(excludedSeatKeys::add);
            if (excludedSeatKeys.size() == beforeExcludeSize) {
                break;
            }
        }
        throw new ServiceException("余票不足或座位冲突，请重试");
    }

    */
    private String buildCarriageSeatKey(TrainPurchaseTicketRespDTO ticket) {
        return ticket.getCarriageNumber() + "#" + ticket.getSeatNumber();
    }

    private void decrementRemainingTicketAfterLock(PurchaseTicketReqDTO requestParam, Integer seatType, int count) {
        if (StrUtil.equals(ticketAvailabilityCacheUpdateType, "binlog")) {
            return;
        }
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        routeDTOList.forEach(each -> {
            String keySuffix = StrUtil.join("_", requestParam.getTrainId(), each.getStartStation(), each.getEndStation());
            stringRedisTemplate.opsForHash().increment(TRAIN_STATION_REMAINING_TICKET + keySuffix, String.valueOf(seatType), -count);
        });
    }

    private List<TrainPurchaseTicketRespDTO> distributeSeatsByRedisBitmap(Integer trainType,
                                                                          Integer seatType,
                                                                          PurchaseTicketReqDTO requestParam,
                                                                          List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails) {
        String strategyKey = VehicleTypeEnum.findNameByCode(trainType) + VehicleSeatTypeEnum.findNameByCode(seatType);
        List<CarriageAvailabilityDTO> candidateCarriages = seatService.listCandidateCarriages(
                requestParam.getTrainId(),
                seatType,
                requestParam.getDeparture(),
                requestParam.getArrival(),
                passengerSeatDetails.size()
        );
        if (CollUtil.isEmpty(candidateCarriages)) {
            throw new ServiceException("站点余票不足或座位资源冲突，请稍后重试");
        }
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
                String holdId = redisSeatBitmapService.tryHold(
                        requestParam.getTrainId(),
                        requestParam.getDeparture(),
                        requestParam.getArrival(),
                        seatType,
                        selectedSeats
                );
                if (StrUtil.isBlank(holdId)) {
                    selectedSeats.stream().map(this::buildCarriageSeatKey).forEach(excludedSeatKeys::add);
                    continue;
                }
                if (seatService.tryLockSeat(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival(), selectedSeats)) {
                    decrementRemainingTicketAfterLock(requestParam, seatType, selectedSeats.size());
                    seatService.adjustCarriageRemainingSummary(
                            requestParam.getTrainId(),
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

    private List<TrainPurchaseTicketRespDTO> distributeSeatsByResourceLocks(Integer trainType,
                                                                            Integer seatType,
                                                                            PurchaseTicketReqDTO requestParam,
                                                                            List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails) {
        String strategyKey =
                VehicleTypeEnum.findNameByCode(trainType) + VehicleSeatTypeEnum.findNameByCode(seatType);
        // 获取区间路段索引列表    b-d会返回 1，2，3
        List<Integer> segmentIndexes = buildSegmentIndexes(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        // 查询当前列车在当前区间下还有哪些车厢值得优先尝试，以及每个车厢当前摘要上还剩多少可用票
        // 03车厢 剩余10张
        // 05车厢 剩余8张
        // 01车厢 剩余6张
        List<CarriageAvailabilityDTO> candidateCarriages = seatService.listCandidateCarriages(
                requestParam.getTrainId(),
                seatType,
                requestParam.getDeparture(),
                requestParam.getArrival(),
                passengerSeatDetails.size()
        );
        if (CollUtil.isEmpty(candidateCarriages)) {
            throw new ServiceException("站点余票不足或座位资源冲突，请稍后重试");
        }
        // 生成一个座位扫描种子，用于在每个车厢内随机扫描座位，避免冲突和死锁
        long scanSeed = buildSeatScanSeed(requestParam, seatType, passengerSeatDetails);
        // 初始化车厢尝试次数
        int carriageAttempt = 0;
        // 开始遍历每个候选车厢
        for (CarriageAvailabilityDTO eachCarriage : candidateCarriages) {
            // 取出当前车厢号
            String carriageNumber = eachCarriage.getCarriageNumber();
            // 尝试获取“车厢 + 区间段”锁集合
            List<RLock> segmentLocks = tryAcquireCarriageSegmentLocks(requestParam.getTrainId(), seatType, carriageNumber, segmentIndexes);
            // 拿不到锁就换车厢重试
            if (CollUtil.isEmpty(segmentLocks)) {
                carriageAttempt++;
                continue;
            }
            try {
                // 本车厢里已经尝试过但锁座失败的那些座位 key。
                Set<String> excludedSeatKeys = new LinkedHashSet<>();
                for (int retry = 0; retry < MAX_CARRIAGE_SELECT_RETRY_TIMES; retry++) {
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
                    if (seatService.tryLockSeat(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival(), selectedSeats)) {
                        // 这里使用canal加binlog日志跟新
                        decrementRemainingTicketAfterLock(requestParam, seatType, selectedSeats.size());
                        // 再扣减当前车厢的摘要余票
                        seatService.adjustCarriageRemainingSummary(
                                requestParam.getTrainId(),
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

    // 尝试获取“车厢 + 区间段”锁集合
    private List<RLock> tryAcquireCarriageSegmentLocks(String trainId, Integer seatType, String carriageNumber, List<Integer> segmentIndexes) {
        List<RLock> locks = new ArrayList<>(segmentIndexes.size());
        for (Integer segmentIndex : segmentIndexes.stream().distinct().sorted().toList()) {
            String lockKey = environment.resolvePlaceholders(String.format(
                    RedisKeyConstant.LOCK_PURCHASE_TICKETS_RESOURCE_SEGMENT,
                    trainId,
                    seatType,
                    carriageNumber,
                    segmentIndex
            ));
            RLock lock = redissonClient.getFairLock(lockKey);
            boolean locked = false;
            try {
                locked = lock.tryLock(RESOURCE_LOCK_WAIT_MILLIS, RESOURCE_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
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
        return (requestParam.getTrainId() + "|" + requestParam.getDeparture() + "|" + requestParam.getArrival()
                + "|" + seatType + "|" + UserContext.getUserId() + "|" + passengerKey).hashCode() & 0x7fffffffL;
    }

    private Integer buildSeatScanOffset(long seed, int carriageAttempt, int retry) {
        long mixed = seed + carriageAttempt * 131L + retry * 17L;
        return (int) Math.floorMod(mixed, Integer.MAX_VALUE);
    }

    private Integer allocateSeatScanOffset(PurchaseTicketReqDTO requestParam, Integer seatType, String carriageNumber,
                                           int passengerCount, long scanSeed, int carriageAttempt, int retry) {
        String cursorKey = TRAIN_CARRIAGE_SEAT_ALLOCATION_CURSOR
                + StrUtil.join("_", requestParam.getTrainId(), seatType, carriageNumber, requestParam.getDeparture(), requestParam.getArrival());
        long step = Math.max(1, passengerCount) * 7L + retry * 13L + 1L;
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        Long cursor = stringRedisTemplate.opsForValue().increment(cursorKey, step);
        stringRedisTemplate.expire(cursorKey, 1, TimeUnit.DAYS);
        long mixed = scanSeed + Optional.ofNullable(cursor).orElse(0L) + carriageAttempt * 131L + retry * 17L;
        return (int) Math.floorMod(mixed, Integer.MAX_VALUE);
    }

    private void enrichPassengerAndPrice(List<TrainPurchaseTicketRespDTO> actualResult, PurchaseTicketReqDTO requestParam) {
        List<String> passengerIds = actualResult.stream().map(TrainPurchaseTicketRespDTO::getPassengerId).toList();
        Result<List<PassengerRespDTO>> passengerRemoteResult;
        List<PassengerRespDTO> passengerRemoteResultList;
        try {
            passengerRemoteResult = userRemoteService.listPassengerQueryByIds(UserContext.getUsername(), passengerIds);
            if (!passengerRemoteResult.isSuccess() || CollUtil.isEmpty(passengerRemoteResultList = passengerRemoteResult.getData())) {
                throw new RemoteException("用户服务远程调用查询乘车人相关信息错误");
            }
        } catch (Throwable ex) {
            if (ex instanceof RemoteException) {
                log.error("用户服务远程调用查询乘车人相关信息错误，当前用户：{}，请求参数：{}", UserContext.getUsername(), passengerIds);
            } else {
                log.error("用户服务远程调用查询乘车人相关信息错误，当前用户：{}，请求参数：{}", UserContext.getUsername(), passengerIds, ex);
            }
            throw ex;
        }
        actualResult.forEach(each -> {
            passengerRemoteResultList.stream()
                    .filter(item -> Objects.equals(item.getId(), each.getPassengerId()))
                    .findFirst()
                    .ifPresent(passenger -> {
                        each.setIdCard(passenger.getIdCard());
                        each.setPhone(passenger.getPhone());
                        each.setUserType(passenger.getDiscountType());
                        each.setIdType(passenger.getIdType());
                        each.setRealName(passenger.getRealName());
                    });
            LambdaQueryWrapper<TrainStationPriceDO> lambdaQueryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                    .eq(TrainStationPriceDO::getTrainId, requestParam.getTrainId())
                    .eq(TrainStationPriceDO::getDeparture, requestParam.getDeparture())
                    .eq(TrainStationPriceDO::getArrival, requestParam.getArrival())
                    .eq(TrainStationPriceDO::getSeatType, each.getSeatType())
                    .select(TrainStationPriceDO::getPrice);
            TrainStationPriceDO trainStationPriceDO = trainStationPriceMapper.selectOne(lambdaQueryWrapper);
            each.setAmount(trainStationPriceDO.getPrice());
        });
    }
}
