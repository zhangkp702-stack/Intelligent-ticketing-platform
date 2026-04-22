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
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationSegmentBitmapUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
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
    private final TrainStationService trainStationService;
    private final RedissonClient redissonClient;
    private final DistributedCache distributedCache;
    private final Cache<String, ReentrantLock> localSeatLockMap = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();

    @Override
    public List<String> listAvailableSeat(String trainId, String carriageNumber, Integer seatType, String departure, String arrival) {
        // 获取当前列车的位图
        long requestMask = buildRequestMask(trainId, departure, arrival);
        // 去数据库中找所有和当前指令与运算之后位0的座位
        List<SeatDO> availableSeats = seatMapper.listAvailableSeatByCarriage(
                Long.parseLong(trainId), carriageNumber, seatType, requestMask, 1000);
        return availableSeats.stream().map(SeatDO::getSeatNumber).collect(Collectors.toList());
    }

    @Override
    public List<Integer> listSeatRemainingTicket(String trainId, String departure, String arrival, List<String> trainCarriageList) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        return seatMapper.listSeatRemainingTicket(Long.parseLong(trainId), requestMask, trainCarriageList);
    }

    @Override
    public List<String> listUsableCarriageNumber(String trainId, Integer carriageType, String departure, String arrival) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        return seatMapper.listUsableCarriageNumber(Long.parseLong(trainId), carriageType, requestMask);
    }

    @Override
    public List<CarriageAvailabilityDTO> listCandidateCarriages(String trainId, Integer seatType, String departure, String arrival, int passengerCount) {
        // 列车站台区间的位图
        long requestMask = buildRequestMask(trainId, departure, arrival);
        // 生成redis的key后缀
        String keySuffix = buildKeySuffix(trainId, departure, arrival, seatType);
        // 生成汇总订单的key
        String summaryKey = TRAIN_STATION_CARRIAGE_REMAINING_TICKET + keySuffix;
        // 从redis中获取redisTemplate
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 获取余票信息
        Map<Object, Object> cachedSummary = stringRedisTemplate.opsForHash().entries(summaryKey);
        if (cachedSummary == null || cachedSummary.isEmpty()) {
            List<CarriageAvailabilityDTO> summaries = seatMapper.listCarriageAvailabilitySummary(Long.parseLong(trainId), seatType, requestMask);
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
            List<CarriageAvailabilityDTO> refreshed = seatMapper.listCarriageAvailabilitySummary(Long.parseLong(trainId), seatType, requestMask);
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
    public void adjustCarriageRemainingSummary(String trainId, String departure, String arrival, Integer seatType, String carriageNumber, long delta) {
        String keySuffix = buildKeySuffix(trainId, departure, arrival, seatType);
        String summaryKey = TRAIN_STATION_CARRIAGE_REMAINING_TICKET + keySuffix;
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        stringRedisTemplate.opsForHash().increment(summaryKey, carriageNumber, delta);
    }

    @Override
    public List<SeatTypeCountDTO> listSeatTypeCount(Long trainId, String startStation, String endStation, List<Integer> seatTypes) {
        long requestMask = buildRequestMask(String.valueOf(trainId), startStation, endStation);
        return seatMapper.listSeatTypeCount(trainId, requestMask, seatTypes);
    }

    @Override
    public boolean tryLockSeat(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        List<Long> lockedSeatIds = new ArrayList<>();
        Long trainIdLong = Long.parseLong(trainId);
        List<TrainPurchaseTicketRespDTO> sortedTickets = tickets.stream()
                .sorted(Comparator.comparing(each -> buildSeatLockKey(trainId, departure, arrival, each)))
                .toList();
        List<ReentrantLock> localLocks = new ArrayList<>(sortedTickets.size());
        List<RLock> distributedLocks = new ArrayList<>(sortedTickets.size());
        sortedTickets.forEach(each -> {
            String seatLockKey = buildSeatLockKey(trainId, departure, arrival, each);
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
                    rollbackLockedSeats(lockedSeatIds, requestMask);
                    return false;
                }
                int updated = seatMapper.tryLockSeatByBitmap(seat.getId(), seat.getVersion(), requestMask);
                if (updated <= 0) {
                    rollbackLockedSeats(lockedSeatIds, requestMask);
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
    public void lockSeat(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        if (!tryLockSeat(trainId, departure, arrival, tickets)) {
            throw new IllegalStateException("座位锁定失败");
        }
    }

    @Override
    public void unlock(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
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
                seatMapper.unlockSeatByBitmap(seat.getId(), requestMask);
            }
        }
    }

    private long buildRequestMask(String trainId, String departure, String arrival) {
        List<String> stationNames = trainStationService.listTrainStationNameByTrainId(trainId);
        return StationSegmentBitmapUtil.buildRequestMask(stationNames, departure, arrival);
    }

    private String buildSeatLockKey(String trainId, String departure, String arrival, TrainPurchaseTicketRespDTO ticket) {
        return String.join(":",
                "index12306-ticket-service",
                "lock",
                "seat",
                trainId,
                departure,
                arrival,
                ticket.getCarriageNumber(),
                ticket.getSeatNumber());
    }

    private String buildKeySuffix(String trainId, String departure, String arrival, Integer seatType) {
        return String.join("_", trainId, departure, arrival, String.valueOf(seatType));
    }

    private void rollbackLockedSeats(List<Long> lockedSeatIds, long requestMask) {
        for (Long seatId : lockedSeatIds) {
            try {
                seatMapper.unlockSeatByBitmap(seatId, requestMask);
            } catch (Exception ex) {
                log.error("回滚已锁定座位失败 seatId={}", seatId, ex);
            }
        }
    }
}
