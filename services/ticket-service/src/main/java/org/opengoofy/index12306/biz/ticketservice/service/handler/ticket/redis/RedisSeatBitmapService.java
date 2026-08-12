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
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatLayoutBitmapUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationSegmentBitmapUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
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

    /**
     * 使用服务端生成的临时标识尝试占用 Redis 座位位图。
     *
     * @param trainId 列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 座位类型
     * @param tickets 待占用座位
     * @param reservationId 不可复用的座位占用标识
     * @return 占位成功时返回 reservationId，冲突时返回空
     */
    public String tryHold(String trainId, String departure, String arrival, Integer seatType, List<TrainPurchaseTicketRespDTO> tickets) {
        return tryHold(trainId, departure, arrival, seatType, tickets, java.util.UUID.randomUUID().toString().replace("-", ""));
    }

    /**
     * 使用 reservationId 尝试占用 Redis 位图，使后续关闭任务可以校验当前 bit 的所有权。
     *
     * @param trainId 列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 座位类型
     * @param tickets 待占用座位
     * @param reservationId 不可复用的座位占用标识
     * @return 占位成功时返回 reservationId，冲突时返回空
     */
    public String tryHold(String trainId, String departure, String arrival, Integer seatType,
                          List<TrainPurchaseTicketRespDTO> tickets, String reservationId) {
        if (CollUtil.isEmpty(tickets)) {
            return null;
        }
        if (StrUtil.isBlank(reservationId)) {
            throw new IllegalArgumentException("reservationId 不能为空");
        }
        // Redis owner 直接写入 reservationId，后续重试无需依赖进程内随机 holdId。
        boolean holdSuccess = executeHold(trainId, departure, arrival, seatType, tickets, reservationId);
        if (!holdSuccess) {
            return null;
        }
        tickets.forEach(each -> each.setRedisSeatHoldId(reservationId));
        return reservationId;
    }

    /**
     * 按座位明细携带的 owner 释放 Redis 位图。
     *
     * @param trainId 列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 座位类型
     * @param tickets 待释放座位
     * @return 当前 owner 是否已被新的 reservation 接管
     */
    public RedisSeatBitmapReleaseResult releaseByHoldId(String trainId, String departure, String arrival,
                                                        Integer seatType, List<TrainPurchaseTicketRespDTO> tickets) {
        String holdId = resolveHoldId(tickets);
        if (StrUtil.isBlank(holdId)) {
            return RedisSeatBitmapReleaseResult.RELEASED;
        }
        return release(trainId, departure, arrival, seatType, tickets, holdId);
    }

    /**
     * 释放本次购票已写入 Redis 的临时座位位图。
     *
     * @param trainId 列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param tickets 待释放座位
     */
    public void releaseHeld(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        if (CollUtil.isEmpty(tickets)) {
            return;
        }
        tickets.stream()
                .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getSeatType))
                .forEach((seatType, eachSeatTypeTickets) -> releaseByHoldId(trainId, departure, arrival, seatType, eachSeatTypeTickets));
    }

    /**
     * 按 reservationId 条件释放 Redis 座位位图。
     *
     * @param trainId 列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param tickets reservation 持有的座位明细
     * @param reservationId 预期的 Redis owner
     * @return 当前 owner 是否已被新的 reservation 接管
     */
    public RedisSeatBitmapReleaseResult releaseByReservationId(String trainId, String departure, String arrival,
                                                                List<TrainPurchaseTicketRespDTO> tickets, String reservationId) {
        if (CollUtil.isEmpty(tickets)) {
            return RedisSeatBitmapReleaseResult.RELEASED;
        }
        if (StrUtil.isBlank(reservationId)) {
            throw new IllegalArgumentException("reservationId 不能为空");
        }
        // 分座位类型执行以复用既有位图 key；任一车厢被新 owner 接管都只返回冲突，不清理对方资源。
        boolean ownerChanged = tickets.stream()
                .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getSeatType))
                .entrySet()
                .stream()
                .map(each -> release(trainId, departure, arrival, each.getKey(), each.getValue(), reservationId))
                .anyMatch(each -> each == RedisSeatBitmapReleaseResult.OWNER_CHANGED);
        return ownerChanged ? RedisSeatBitmapReleaseResult.OWNER_CHANGED : RedisSeatBitmapReleaseResult.RELEASED;
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
        Long result = stringRedisTemplate.execute(script, keys, holdId, seatBits);
        return Objects.equals(result, 1L);
    }

    /**
     * 按同一车厢执行一次 owner 条件释放脚本。
     *
     * @param trainId 列车标识
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 座位类型
     * @param tickets 当前座位类型的座位明细
     * @param reservationId 预期 Redis owner
     * @return 释放结果
     */
    private RedisSeatBitmapReleaseResult release(String trainId, String departure, String arrival, Integer seatType,
                                                 List<TrainPurchaseTicketRespDTO> tickets, String reservationId) {
        if (CollUtil.isEmpty(tickets)) {
            return RedisSeatBitmapReleaseResult.RELEASED;
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
            boolean ownerChanged = false;
            for (List<TrainPurchaseTicketRespDTO> eachCarriageTickets : carriageTickets.values()) {
                List<String> keys = buildKeys(trainId, departure, arrival, seatType, eachCarriageTickets);
                String seatBits = buildSeatBits(seatType, eachCarriageTickets);
                Long result = stringRedisTemplate.execute(script, keys, reservationId, seatBits);
                if (result == null || result < 0) {
                    throw new IllegalStateException("Redis 座位位图释放脚本执行失败");
                }
                ownerChanged = ownerChanged || Objects.equals(result, 2L);
            }
            return ownerChanged ? RedisSeatBitmapReleaseResult.OWNER_CHANGED : RedisSeatBitmapReleaseResult.RELEASED;
        } catch (Throwable ex) {
            log.warn("Release Redis seat bitmap failed, trainId={}, departure={}, arrival={}, seatType={}",
                    trainId, departure, arrival, seatType, ex);
            throw new IllegalStateException("释放 Redis 座位位图失败", ex);
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
