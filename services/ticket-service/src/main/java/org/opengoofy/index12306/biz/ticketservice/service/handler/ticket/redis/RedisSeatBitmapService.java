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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatLayoutBitmapUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationSegmentBitmapUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEGMENT_SEAT_BITMAP;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEGMENT_SEAT_OWNER;

/**
 * Redis bitmap temporary seat hold service.
 * DB occupy_bitmap remains the source of truth; Redis is the fast conflict guard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSeatBitmapService {

    private static final String LUA_SEAT_BITMAP_HOLD_PATH = "lua/seat_bitmap_hold.lua";
    private static final String LUA_SEAT_BITMAP_RELEASE_PATH = "lua/seat_bitmap_release.lua";

    private final DistributedCache distributedCache;
    private final TrainStationService trainStationService;

    @Value("${ticket.seat.redis-bitmap-hold.ttl-seconds:900}")
    private Long holdTtlSeconds;

    public String tryHold(String trainId, String departure, String arrival, Integer seatType, List<TrainPurchaseTicketRespDTO> tickets) {
        if (CollUtil.isEmpty(tickets)) {
            return null;
        }
        String holdId = UUID.randomUUID().toString().replace("-", "");
        boolean holdSuccess = executeHold(trainId, departure, arrival, seatType, tickets, holdId);
        if (!holdSuccess) {
            return null;
        }
        tickets.forEach(each -> each.setRedisSeatHoldId(holdId));
        return holdId;
    }

    public void releaseByHoldId(String trainId, String departure, String arrival, Integer seatType, List<TrainPurchaseTicketRespDTO> tickets) {
        release(trainId, departure, arrival, seatType, tickets, resolveHoldId(tickets));
    }

    public void releaseHeld(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        if (CollUtil.isEmpty(tickets)) {
            return;
        }
        tickets.stream()
                .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getSeatType))
                .forEach((seatType, eachSeatTypeTickets) -> releaseByHoldId(trainId, departure, arrival, seatType, eachSeatTypeTickets));
    }

    public void releaseAnyway(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        if (CollUtil.isEmpty(tickets)) {
            return;
        }
        tickets.stream()
                .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getSeatType))
                .forEach((seatType, eachSeatTypeTickets) -> release(trainId, departure, arrival, seatType, eachSeatTypeTickets, ""));
    }

    private boolean executeHold(String trainId, String departure, String arrival, Integer seatType,
                                List<TrainPurchaseTicketRespDTO> tickets, String holdId) {
        DefaultRedisScript<Long> script = Singleton.get(LUA_SEAT_BITMAP_HOLD_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_SEAT_BITMAP_HOLD_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
        Assert.notNull(script);
        List<String> keys = buildKeys(trainId, departure, arrival, seatType, tickets);
        String seatBits = buildSeatBits(seatType, tickets);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        Long result = stringRedisTemplate.execute(script, keys, holdId, seatBits, String.valueOf(holdTtlSeconds));
        return Objects.equals(result, 1L);
    }

    private void release(String trainId, String departure, String arrival, Integer seatType,
                         List<TrainPurchaseTicketRespDTO> tickets, String holdId) {
        if (CollUtil.isEmpty(tickets)) {
            return;
        }
        try {
            DefaultRedisScript<Long> script = Singleton.get(LUA_SEAT_BITMAP_RELEASE_PATH, () -> {
                DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_SEAT_BITMAP_RELEASE_PATH)));
                redisScript.setResultType(Long.class);
                return redisScript;
            });
            Assert.notNull(script);
            Map<String, List<TrainPurchaseTicketRespDTO>> carriageTickets = tickets.stream()
                    .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getCarriageNumber, LinkedHashMap::new, Collectors.toList()));
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            for (List<TrainPurchaseTicketRespDTO> eachCarriageTickets : carriageTickets.values()) {
                List<String> keys = buildKeys(trainId, departure, arrival, seatType, eachCarriageTickets);
                String seatBits = buildSeatBits(seatType, eachCarriageTickets);
                stringRedisTemplate.execute(script, keys, holdId == null ? "" : holdId, seatBits);
            }
        } catch (Throwable ex) {
            log.warn("Release Redis seat bitmap failed, trainId={}, departure={}, arrival={}, seatType={}",
                    trainId, departure, arrival, seatType, ex);
        }
    }

    private List<String> buildKeys(String trainId, String departure, String arrival, Integer seatType,
                                   List<TrainPurchaseTicketRespDTO> tickets) {
        String carriageNumber = tickets.get(0).getCarriageNumber();
        List<Integer> segmentIndexes = buildSegmentIndexes(trainId, departure, arrival);
        List<String> bitmapKeys = new ArrayList<>(segmentIndexes.size());
        List<String> ownerKeys = new ArrayList<>(segmentIndexes.size());
        segmentIndexes.forEach(segmentIndex -> {
            bitmapKeys.add(String.format(TRAIN_CARRIAGE_SEGMENT_SEAT_BITMAP, trainId, seatType, carriageNumber, segmentIndex));
            ownerKeys.add(String.format(TRAIN_CARRIAGE_SEGMENT_SEAT_OWNER, trainId, seatType, carriageNumber, segmentIndex));
        });
        bitmapKeys.addAll(ownerKeys);
        return bitmapKeys;
    }

    private String buildSeatBits(Integer seatType, List<TrainPurchaseTicketRespDTO> tickets) {
        SeatLayoutBitmapUtil.LayoutProfile profile = SeatLayoutBitmapUtil.profileBySeatType(seatType);
        return tickets.stream()
                .map(TrainPurchaseTicketRespDTO::getSeatNumber)
                .map(each -> SeatLayoutBitmapUtil.parseSeatCoordinate(each, profile))
                .map(each -> each.rowIndex() * profile.colCount() + each.colIndex())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private List<Integer> buildSegmentIndexes(String trainId, String departure, String arrival) {
        List<String> stationNames = trainStationService.listTrainStationNameByTrainId(trainId);
        Map<String, Integer> stationIndexMap = StationSegmentBitmapUtil.buildStationIndexMap(stationNames);
        Integer departureIndex = stationIndexMap.get(departure);
        Integer arrivalIndex = stationIndexMap.get(arrival);
        List<Integer> segmentIndexes = new ArrayList<>(arrivalIndex - departureIndex);
        for (int i = departureIndex; i < arrivalIndex; i++) {
            segmentIndexes.add(i);
        }
        return segmentIndexes;
    }

    private String resolveHoldId(List<TrainPurchaseTicketRespDTO> tickets) {
        return tickets.stream()
                .map(TrainPurchaseTicketRespDTO::getRedisSeatHoldId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
    }
}
