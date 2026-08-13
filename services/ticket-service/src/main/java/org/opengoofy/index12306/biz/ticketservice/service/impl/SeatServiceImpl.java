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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageAvailabilityDTO;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainSeatOccupancyDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainSeatOccupancyMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationSegmentBitmapUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_CARRIAGE_REMAINING_TICKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_CARRIAGE_REMAINING_TICKET_CURSOR;

/**
 * 座位接口层实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatServiceImpl extends ServiceImpl<SeatMapper, SeatDO> implements SeatService {

    private final SeatMapper seatMapper;
    private final TrainSeatOccupancyMapper trainSeatOccupancyMapper;
    private final TrainStationService trainStationService;
    private final RedissonClient redissonClient;
    private final DistributedCache distributedCache;
    private final Cache<String, ReentrantLock> localSeatLockMap = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();
    private final Cache<String, Boolean> initializedServiceDateInventory = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    @Override
    public List<String> listAvailableSeat(String trainId, Date serviceDate, String carriageNumber, Integer seatType, String departure, String arrival) {
        // 获取当前列车的位图
        long requestMask = buildRequestMask(trainId, departure, arrival);
        // 去数据库中找所有和当前指令与运算之后位0的座位
        ensureServiceDateInventory(trainId, serviceDate);
        List<SeatDO> availableSeats = serviceDate == null
                ? seatMapper.listAvailableSeatByCarriage(Long.parseLong(trainId), carriageNumber, seatType, requestMask, 1000)
                : trainSeatOccupancyMapper.listAvailableSeatByCarriage(Long.parseLong(trainId), serviceDate, carriageNumber, seatType, requestMask, 1000);
        return availableSeats.stream().map(SeatDO::getSeatNumber).collect(Collectors.toList());
    }

    @Override
    public List<Integer> listSeatRemainingTicket(String trainId, Date serviceDate, String departure, String arrival, List<String> trainCarriageList) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        ensureServiceDateInventory(trainId, serviceDate);
        if (serviceDate == null) {
            return seatMapper.listSeatRemainingTicket(Long.parseLong(trainId), requestMask, trainCarriageList);
        }
        return trainSeatOccupancyMapper.listSeatRemainingTicket(Long.parseLong(trainId), serviceDate, requestMask, trainCarriageList);
    }

    @Override
    public List<String> listUsableCarriageNumber(String trainId, Date serviceDate, Integer carriageType, String departure, String arrival) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        ensureServiceDateInventory(trainId, serviceDate);
        if (serviceDate == null) {
            return seatMapper.listUsableCarriageNumber(Long.parseLong(trainId), carriageType, requestMask);
        }
        return trainSeatOccupancyMapper.listCarriageAvailabilitySummary(Long.parseLong(trainId), serviceDate, carriageType, requestMask).stream()
                .map(CarriageAvailabilityDTO::getCarriageNumber)
                .collect(Collectors.toList());
    }

    @Override
    public List<CarriageAvailabilityDTO> listCandidateCarriages(String trainId, Date serviceDate, Integer seatType, String departure, String arrival, int passengerCount) {
        // 列车站台区间的位图
        long requestMask = buildRequestMask(trainId, departure, arrival);
        // 生成redis的key后缀
        ensureServiceDateInventory(trainId, serviceDate);
        String keySuffix = buildKeySuffix(trainId, serviceDate, departure, arrival, seatType);
        // 生成汇总订单的key
        String summaryKey = TRAIN_STATION_CARRIAGE_REMAINING_TICKET + keySuffix;
        // 从redis中获取redisTemplate
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 获取余票信息
        Map<Object, Object> cachedSummary = stringRedisTemplate.opsForHash().entries(summaryKey);
        if (cachedSummary == null || cachedSummary.isEmpty()) {
            List<CarriageAvailabilityDTO> summaries = queryCarriageAvailability(trainId, serviceDate, seatType, requestMask);
            if (!summaries.isEmpty()) {
                Map<String, String> summaryMap = summaries.stream().collect(Collectors.toMap(
                        CarriageAvailabilityDTO::getCarriageNumber,
                        each -> String.valueOf(each.getSeatCount()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
                stringRedisTemplate.opsForHash().putAll(summaryKey, summaryMap);
                cachedSummary = new LinkedHashMap<>(summaryMap);
            } else {
                cachedSummary = Collections.emptyMap();
            }
        }
        List<CarriageAvailabilityDTO> candidates = cachedSummary.entrySet().stream()
                .map(each -> new CarriageAvailabilityDTO(String.valueOf(each.getKey()), Integer.parseInt(String.valueOf(each.getValue()))))
                // 只保留余票数大于等于本次购票人数的车厢。
                .filter(each -> each.getSeatCount() >= passengerCount)
                // 余票数多的优先, 余票相同时，车厢号小的优先
                .sorted(Comparator.comparingInt(CarriageAvailabilityDTO::getSeatCount).reversed()
                        .thenComparing(CarriageAvailabilityDTO::getCarriageNumber))
                .collect(Collectors.toList());
        // 如果筛完一个候选都没有，主动再查一次数据库
        if (candidates.isEmpty()) {
            List<CarriageAvailabilityDTO> refreshed = queryCarriageAvailability(trainId, serviceDate, seatType, requestMask);
            if (!refreshed.isEmpty()) {
                Map<String, String> refreshedSummaryMap = refreshed.stream().collect(Collectors.toMap(
                        CarriageAvailabilityDTO::getCarriageNumber,
                        each -> String.valueOf(each.getSeatCount()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
                stringRedisTemplate.opsForHash().putAll(summaryKey, refreshedSummaryMap);
                candidates = refreshed.stream()
                        .filter(each -> each.getSeatCount() >= passengerCount)
                        .sorted(Comparator.comparingInt(CarriageAvailabilityDTO::getSeatCount).reversed()
                                .thenComparing(CarriageAvailabilityDTO::getCarriageNumber))
                        .collect(Collectors.toList());
            }
        }
        // 如果有多个候选车厢，用游标轮转打散顺序
        if (candidates.size() > 1) {
            String cursorKey = TRAIN_STATION_CARRIAGE_REMAINING_TICKET_CURSOR + keySuffix;
            Long cursor = stringRedisTemplate.opsForValue().increment(cursorKey);
            if (cursor != null) {
                int offset = Math.floorMod(cursor.intValue(), candidates.size());
                if (offset > 0) {
                    Collections.rotate(candidates, -offset);
                }
            }
        }
        // 当前请求下，哪些车厢值得优先尝试，以及每个车厢当前摘要上还剩多少可用票。
        return candidates;
    }

    @Override
    public void adjustCarriageRemainingSummary(String trainId, Date serviceDate, String departure, String arrival, Integer seatType, String carriageNumber, long delta) {
        String keySuffix = buildKeySuffix(trainId, serviceDate, departure, arrival, seatType);
        String summaryKey = TRAIN_STATION_CARRIAGE_REMAINING_TICKET + keySuffix;
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        stringRedisTemplate.opsForHash().increment(summaryKey, carriageNumber, delta);
    }

    @Override
    public List<SeatTypeCountDTO> listSeatTypeCount(Long trainId, Date serviceDate, String startStation, String endStation, List<Integer> seatTypes) {
        long requestMask = buildRequestMask(String.valueOf(trainId), startStation, endStation);
        ensureServiceDateInventory(String.valueOf(trainId), serviceDate);
        return serviceDate == null
                ? seatMapper.listSeatTypeCount(trainId, requestMask, seatTypes)
                : trainSeatOccupancyMapper.listSeatTypeCount(trainId, serviceDate, requestMask, seatTypes);
    }

    @Override
    public boolean tryLockSeat(String trainId, Date serviceDate, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        List<Long> lockedSeatIds = new ArrayList<>();
        Long trainIdLong = Long.parseLong(trainId);
        ensureServiceDateInventory(trainId, serviceDate);
        List<TrainPurchaseTicketRespDTO> sortedTickets = tickets.stream()
                .sorted(Comparator.comparing(each -> buildSeatLockKey(trainId, serviceDate, departure, arrival, each)))
                .toList();
        List<ReentrantLock> localLocks = new ArrayList<>(sortedTickets.size());
        List<RLock> distributedLocks = new ArrayList<>(sortedTickets.size());
        sortedTickets.forEach(each -> {
            String seatLockKey = buildSeatLockKey(trainId, serviceDate, departure, arrival, each);
            localLocks.add(localSeatLockMap.get(seatLockKey, key -> new ReentrantLock(true)));
            distributedLocks.add(redissonClient.getFairLock(seatLockKey));
        });
        try {
            localLocks.forEach(ReentrantLock::lock);
            distributedLocks.forEach(RLock::lock);
            for (TrainPurchaseTicketRespDTO ticket : sortedTickets) {
                SeatDO seat = seatMapper.selectOne(Wrappers.lambdaQuery(SeatDO.class)
                        .eq(SeatDO::getTrainId, trainIdLong)
                        .eq(SeatDO::getCarriageNumber, ticket.getCarriageNumber())
                        .eq(SeatDO::getSeatNumber, ticket.getSeatNumber())
                        .eq(SeatDO::getSeatType, ticket.getSeatType()));
                if (seat == null) {
                    rollbackLockedSeats(trainIdLong, serviceDate, lockedSeatIds, requestMask);
                    return false;
                }
                int updated = tryLockSeatByBitmap(trainIdLong, serviceDate, seat, requestMask);
                if (updated <= 0) {
                    rollbackLockedSeats(trainIdLong, serviceDate, lockedSeatIds, requestMask);
                    return false;
                }
                lockedSeatIds.add(seat.getId());
            }
        } finally {
            for (int i = localLocks.size() - 1; i >= 0; i--) {
                try {
                    localLocks.get(i).unlock();
                } catch (Throwable ignored) {
                }
            }
            for (int i = distributedLocks.size() - 1; i >= 0; i--) {
                try {
                    distributedLocks.get(i).unlock();
                } catch (Throwable ignored) {
                }
            }
        }
        return true;
    }

    @Override
    public void lockSeat(String trainId, Date serviceDate, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        if (!tryLockSeat(trainId, serviceDate, departure, arrival, tickets)) {
            throw new IllegalStateException("座位锁定失败");
        }
    }

    @Override
    public void unlock(String trainId, Date serviceDate, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        Long trainIdLong = Long.parseLong(trainId);
        for (TrainPurchaseTicketRespDTO ticket : tickets) {
            LambdaQueryWrapper<SeatDO> queryWrapper = Wrappers.lambdaQuery(SeatDO.class)
                    .eq(SeatDO::getTrainId, trainIdLong)
                    .eq(SeatDO::getCarriageNumber, ticket.getCarriageNumber())
                    .eq(SeatDO::getSeatNumber, ticket.getSeatNumber())
                    .eq(SeatDO::getSeatType, ticket.getSeatType());
            SeatDO seat = seatMapper.selectOne(queryWrapper);
            if (seat != null) {
                unlockSeatByBitmap(trainIdLong, serviceDate, seat.getId(), requestMask);
            }
        }
    }

    private long buildRequestMask(String trainId, String departure, String arrival) {
        List<String> stationNames = trainStationService.listTrainStationNameByTrainId(trainId);
        return StationSegmentBitmapUtil.buildRequestMask(stationNames, departure, arrival);
    }

    /**
     * 构造带始发日期的单座位锁键，避免不同开行日相互串行。
     */
    private String buildSeatLockKey(String trainId, Date serviceDate, String departure, String arrival, TrainPurchaseTicketRespDTO ticket) {
        return String.join(":",
                "index12306-ticket-service",
                "lock",
                "seat",
                trainId,
                ServiceDateKeyUtil.format(serviceDate),
                departure,
                arrival,
                ticket.getCarriageNumber(),
                ticket.getSeatNumber());
    }

    /**
     * 构造车厢余票摘要的日期隔离键后缀。
     */
    private String buildKeySuffix(String trainId, Date serviceDate, String departure, String arrival, Integer seatType) {
        return String.join("_", trainId, ServiceDateKeyUtil.format(serviceDate), departure, arrival, String.valueOf(seatType));
    }

    /**
     * 初始化某个始发日期的运行库存，重复初始化不会覆盖已有占用状态。
     */
    private void ensureServiceDateInventory(String trainId, Date serviceDate) {
        if (serviceDate == null) {
            return;
        }
        String inventoryKey = trainId + ':' + ServiceDateKeyUtil.format(serviceDate);
        if (initializedServiceDateInventory.getIfPresent(inventoryKey) == null) {
            trainSeatOccupancyMapper.initializeServiceDateInventory(Long.parseLong(trainId), serviceDate);
            initializedServiceDateInventory.put(inventoryKey, Boolean.TRUE);
        }
    }

    /**
     * 查询指定始发日期下的车厢可用座位摘要。
     */
    private List<CarriageAvailabilityDTO> queryCarriageAvailability(String trainId, Date serviceDate, Integer seatType, long requestMask) {
        return serviceDate == null
                ? seatMapper.listCarriageAvailabilitySummary(Long.parseLong(trainId), seatType, requestMask)
                : trainSeatOccupancyMapper.listCarriageAvailabilitySummary(Long.parseLong(trainId), serviceDate, seatType, requestMask);
    }

    /**
     * 以日期运行库存的版本号确认座位，历史预订记录仍保留旧表回退路径。
     */
    private int tryLockSeatByBitmap(Long trainId, Date serviceDate, SeatDO seat, long requestMask) {
        if (serviceDate == null) {
            return seatMapper.tryLockSeatByBitmap(seat.getId(), seat.getVersion(), requestMask);
        }
        TrainSeatOccupancyDO occupancy = trainSeatOccupancyMapper.selectOne(Wrappers.lambdaQuery(TrainSeatOccupancyDO.class)
                .eq(TrainSeatOccupancyDO::getTrainId, trainId)
                .eq(TrainSeatOccupancyDO::getServiceDate, serviceDate)
                .eq(TrainSeatOccupancyDO::getSeatId, seat.getId()));
        return occupancy == null ? 0 : trainSeatOccupancyMapper.tryLockSeatByBitmap(trainId, serviceDate, seat.getId(), occupancy.getVersion(), requestMask);
    }

    /**
     * 释放指定始发日期的运行库存位图；历史记录继续释放旧位图。
     */
    private void unlockSeatByBitmap(Long trainId, Date serviceDate, Long seatId, long requestMask) {
        if (serviceDate == null) {
            seatMapper.unlockSeatByBitmap(seatId, requestMask);
            return;
        }
        trainSeatOccupancyMapper.unlockSeatByBitmap(trainId, serviceDate, seatId, requestMask);
    }

    /**
     * 回滚当前批次已经确认的座位，确保不会污染同始发日的其他请求。
     */
    private void rollbackLockedSeats(Long trainId, Date serviceDate, List<Long> lockedSeatIds, long requestMask) {
        for (Long seatId : lockedSeatIds) {
            try {
                unlockSeatByBitmap(trainId, serviceDate, seatId, requestMask);
            } catch (Exception ex) {
                log.error("回滚已锁定座位失败 seatId={}", seatId, ex);
            }
        }
    }
}
