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
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatLayoutBitmapUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationSegmentBitmapUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEGMENT_SEAT_BITMAP;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEGMENT_SEAT_OWNER;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEGMENT_SEAT_BITMAP_BY_SERVICE_DATE;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEGMENT_SEAT_OWNER_BY_SERVICE_DATE;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEAT_LEASE_INDEX;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEAT_LEASE_INDEX_LEGACY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEAT_LEASE_PAYLOAD;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE_SEAT_LEASE_PAYLOAD_LEGACY;

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
    private static final String LUA_SEAT_BITMAP_LEASE_CONFIRM_PATH = "lua/seat_bitmap_lease_confirm.lua";
    private static final String LEASE_INDEX_KEY_PREFIX = "index12306-ticket-service:seat_lease_index:";
    private static final String LEASE_PAYLOAD_KEY_PREFIX = "index12306-ticket-service:seat_lease_payload:";

    private final DistributedCache distributedCache;
    private final TrainStationService trainStationService;

    @Value("${index12306.ticket.redis-seat-lease.lease-millis:120000}")
    private long redisSeatLeaseMillis;

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
        return tryHold(trainId, null, departure, arrival, seatType, tickets, reservationId);
    }

    /**
     * 在指定始发日期的 Redis 运行库存中尝试占用座位。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 座位类型
     * @param tickets 待占用座位
     * @param reservationId 座位归属标识
     * @return 占用成功时返回 reservationId，冲突时返回空
     */
    public String tryHold(String trainId, Date serviceDate, String departure, String arrival, Integer seatType,
                          List<TrainPurchaseTicketRespDTO> tickets, String reservationId) {
        if (CollUtil.isEmpty(tickets)) {
            return null;
        }
        if (StrUtil.isBlank(reservationId)) {
            throw new IllegalArgumentException("reservationId 不能为空");
        }
        // Redis owner 直接写入 reservationId，后续重试无需依赖进程内随机 holdId。
        boolean holdSuccess = executeHold(trainId, serviceDate, departure, arrival, seatType, tickets, reservationId);
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
        return releaseByHoldId(trainId, null, departure, arrival, seatType, tickets);
    }

    /**
     * 根据临时占用标识释放指定始发日期的 Redis 座位位图。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 座位类型
     * @param tickets 待释放座位
     * @return 释放结果
     */
    public RedisSeatBitmapReleaseResult releaseByHoldId(String trainId, Date serviceDate, String departure, String arrival,
                                                        Integer seatType, List<TrainPurchaseTicketRespDTO> tickets) {
        String holdId = resolveHoldId(tickets);
        if (StrUtil.isBlank(holdId)) {
            return RedisSeatBitmapReleaseResult.RELEASED;
        }
        return release(trainId, serviceDate, departure, arrival, seatType, tickets, holdId);
    }

    /**
     * 释放指定始发日期下本次购票已持有的全部 Redis 临时座位。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 本次购票统一使用的席别
     * @param tickets 待释放座位
     */
    public void releaseHeld(String trainId, Date serviceDate, String departure, String arrival, Integer seatType,
                            List<TrainPurchaseTicketRespDTO> tickets) {
        if (CollUtil.isEmpty(tickets)) {
            return;
        }
        // 每个订单只持有一种席别，直接使用订单根席别释放各车厢位图。
        releaseByHoldId(trainId, serviceDate, departure, arrival, seatType, tickets);
    }

    /**
     * 仅当 owner 与 reservationId 一致时，释放指定始发日期下的 Redis 座位位图。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType reservation 统一使用的席别
     * @param tickets 预订关系持有的座位
     * @param reservationId 预期 Redis owner
     * @return 释放结果
     */
    public RedisSeatBitmapReleaseResult releaseByReservationId(String trainId, Date serviceDate, String departure, String arrival,
                                                                Integer seatType, List<TrainPurchaseTicketRespDTO> tickets,
                                                                String reservationId) {
        if (CollUtil.isEmpty(tickets)) {
            return RedisSeatBitmapReleaseResult.RELEASED;
        }
        if (StrUtil.isBlank(reservationId)) {
            throw new IllegalArgumentException("reservationId 不能为空");
        }
        // 统一席别复用同一位图 key；任一车厢被新 owner 接管都只返回冲突，不清理对方资源。
        return release(trainId, serviceDate, departure, arrival, seatType, tickets, reservationId);
    }

    /**
     * 确认本次 Redis 临时占座已进入 MySQL 可恢复状态，并原子移除短期租约。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 座位类型
     * @param tickets 已提交的座位明细
     * @param reservationId 稳定预订标识
     */
    public void confirmLease(String trainId, Date serviceDate, String departure, String arrival, Integer seatType,
                             List<TrainPurchaseTicketRespDTO> tickets, String reservationId) {
        if (CollUtil.isEmpty(tickets) || StrUtil.isBlank(reservationId)) {
            return;
        }
        List<String> keys = buildKeys(trainId, serviceDate, departure, arrival, seatType, tickets);
        confirmLease(keys.subList(keys.size() - 2, keys.size()), reservationId);
    }

    /**
     * 确认扫描器发现的已持久化座位租约，保留 bitmap 和 owner，仅停止过期回收。
     *
     * @param lease Redis 中原子写入的租约上下文
     */
    public void confirmLease(RedisSeatLease lease) {
        if (lease == null || StrUtil.isBlank(lease.getReservationId())) {
            return;
        }
        List<String> keys = buildKeys(lease.getTrainId(), lease.getServiceDateKey(), lease.getDeparture(),
                lease.getArrival(), lease.getSeatType(), lease.getCarriageNumber());
        confirmLease(keys.subList(keys.size() - 2, keys.size()), lease.getReservationId());
    }

    /**
     * 有界读取已经超过处理租约的 Redis 临时占座上下文。
     *
     * @param limit 单轮最多返回的租约数量
     * @return 可用于 owner 校验释放的过期租约
     */
    public List<RedisSeatLease> listExpiredLeases(int limit) {
        int scanLimit = Math.max(1, limit);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        ScanOptions options = ScanOptions.scanOptions().match(LEASE_INDEX_KEY_PREFIX + "*").count(scanLimit).build();
        List<RedisSeatLease> result = new ArrayList<>(scanLimit);
        try (Cursor<String> leaseIndexKeys = stringRedisTemplate.scan(options)) {
            int scanned = 0;
            while (leaseIndexKeys.hasNext() && scanned++ < scanLimit && result.size() < scanLimit) {
                String leaseIndexKey = leaseIndexKeys.next();
                int remaining = scanLimit - result.size();
                Set<String> reservationIds = stringRedisTemplate.opsForZSet().rangeByScore(
                        leaseIndexKey, Double.NEGATIVE_INFINITY, System.currentTimeMillis(), 0, remaining);
                if (CollUtil.isEmpty(reservationIds)) {
                    continue;
                }
                String leasePayloadKey = buildLeasePayloadKey(leaseIndexKey);
                List<Object> payloads = stringRedisTemplate.opsForHash().multiGet(
                        leasePayloadKey, new ArrayList<>(new LinkedHashSet<>(reservationIds)));
                if (payloads == null) {
                    continue;
                }
                for (Object each : payloads) {
                    if (each == null) {
                        log.error("ticket_redis_seat_lease_payload_missing leaseIndexKey={}", leaseIndexKey);
                        continue;
                    }
                    try {
                        RedisSeatLease lease = JSON.parseObject(String.valueOf(each), RedisSeatLease.class);
                        if (lease != null && StrUtil.isNotBlank(lease.getReservationId())) {
                            result.add(lease);
                        }
                    } catch (RuntimeException ex) {
                        log.error("ticket_redis_seat_lease_payload_invalid leaseIndexKey={}, payload={}",
                                leaseIndexKey, each, ex);
                    }
                    if (result.size() >= scanLimit) {
                        break;
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("ticket_redis_seat_lease_scan_failed", ex);
        }
        return result;
    }

    /**
     * 按已过期的原始租约上下文释放 Redis bit 和 owner，避免依赖尚未来得及写入 MySQL 的座位快照。
     *
     * @param lease Redis Lua 原子保存的租约上下文
     */
    public void releaseExpiredLease(RedisSeatLease lease) {
        if (lease == null || StrUtil.isBlank(lease.getReservationId()) || StrUtil.isBlank(lease.getSeatBits())) {
            throw new IllegalArgumentException("过期 Redis 座位租约缺少必要信息");
        }
        List<String> keys = buildKeys(lease.getTrainId(), lease.getServiceDateKey(), lease.getDeparture(),
                lease.getArrival(), lease.getSeatType(), lease.getCarriageNumber());
        Long result = executeReleaseScript(keys, lease.getReservationId(), lease.getSeatBits());
        if (result == null || result < 0) {
            throw new IllegalStateException("Redis 过期座位租约释放脚本执行失败");
        }
    }

    /**
     * 执行 Lua 原子校验与占位，键空间由始发日期隔离。
     */
    private boolean executeHold(String trainId, Date serviceDate, String departure, String arrival, Integer seatType,
                                List<TrainPurchaseTicketRespDTO> tickets, String holdId) {
        DefaultRedisScript<Long> script = Singleton.get(LUA_SEAT_BITMAP_HOLD_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_SEAT_BITMAP_HOLD_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
        Assert.notNull(script);
        List<String> keys = buildKeys(trainId, serviceDate, departure, arrival, seatType, tickets);
        String seatBits = buildSeatBits(seatType, tickets);
        RedisSeatLease lease = RedisSeatLease.builder()
                .reservationId(holdId)
                .trainId(trainId)
                .serviceDateKey(ServiceDateKeyUtil.format(serviceDate))
                .departure(departure)
                .arrival(arrival)
                .seatType(seatType)
                .carriageNumber(tickets.get(0).getCarriageNumber())
                .seatBits(seatBits)
                .build();
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        long leaseExpireAt = System.currentTimeMillis() + Math.max(1L, redisSeatLeaseMillis);
        Long result = stringRedisTemplate.execute(script, keys, holdId, seatBits,
                String.valueOf(leaseExpireAt), JSON.toJSONString(lease));
        return Objects.equals(result, 1L);
    }

    /**
     * 按同一车厢执行 owner 校验后的 Lua 释放，防止重复消息释放新请求持有的座位。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 订单统一使用的席别
     * @param tickets 同一订单的座位明细
     * @param reservationId 预期 Redis owner
     * @return 释放结果
     */
    private RedisSeatBitmapReleaseResult release(String trainId, Date serviceDate, String departure, String arrival, Integer seatType,
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
                List<String> keys = buildKeys(trainId, serviceDate, departure, arrival, seatType, eachCarriageTickets);
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

    /**
     * 执行同 slot 的租约确认脚本，避免 ZSet 与元数据 Hash 只删除其一。
     *
     * @param leaseKeys 租约索引和元数据键
     * @param reservationId 当前 owner 标识
     */
    private void confirmLease(List<String> leaseKeys, String reservationId) {
        DefaultRedisScript<Long> script = Singleton.get(LUA_SEAT_BITMAP_LEASE_CONFIRM_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(
                    new ClassPathResource(LUA_SEAT_BITMAP_LEASE_CONFIRM_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
        Assert.notNull(script);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        Long result = stringRedisTemplate.execute(script, leaseKeys, reservationId);
        if (!Objects.equals(result, 1L)) {
            throw new IllegalStateException("Redis 座位租约确认脚本执行失败");
        }
    }

    /**
     * 执行 owner 校验后的位图释放脚本，并同时移除对应租约。
     *
     * @param keys 位图、owner 和租约键
     * @param reservationId 预期 owner
     * @param seatBits 待释放的位图下标
     * @return Lua 执行结果
     */
    private Long executeReleaseScript(List<String> keys, String reservationId, String seatBits) {
        DefaultRedisScript<Long> script = Singleton.get(LUA_SEAT_BITMAP_RELEASE_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_SEAT_BITMAP_RELEASE_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
        Assert.notNull(script);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        return stringRedisTemplate.execute(script, keys, reservationId, seatBits);
    }

    /**
     * 构造同一始发日期、同一车厢的 bitmap 和 owner 键，保证 Lua 脚本仅操作该运行库存。
     */
    private List<String> buildKeys(String trainId, Date serviceDate, String departure, String arrival, Integer seatType,
                                   List<TrainPurchaseTicketRespDTO> tickets) {
        return buildKeys(trainId, ServiceDateKeyUtil.format(serviceDate), departure, arrival, seatType,
                tickets.get(0).getCarriageNumber());
    }

    /**
     * 构造单个车厢运行库存分片的位图、owner 和租约键。
     *
     * @param trainId 列车标识
     * @param serviceDateKey 列车始发日期键片段，空串表示历史无日期库存
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 座位类型
     * @param carriageNumber 车厢号
     * @return 保持同一 Redis Cluster slot 的脚本键列表
     */
    private List<String> buildKeys(String trainId, String serviceDateKey, String departure, String arrival, Integer seatType,
                                   String carriageNumber) {
        List<Integer> segmentIndexes = buildSegmentIndexes(trainId, departure, arrival);
        List<String> bitmapKeys = new ArrayList<>(segmentIndexes.size() * 2 + 2);
        List<String> ownerKeys = new ArrayList<>(segmentIndexes.size());
        segmentIndexes.forEach(segmentIndex -> {
            if (StrUtil.isBlank(serviceDateKey)) {
                bitmapKeys.add(String.format(TRAIN_CARRIAGE_SEGMENT_SEAT_BITMAP, trainId, seatType, carriageNumber, segmentIndex));
                ownerKeys.add(String.format(TRAIN_CARRIAGE_SEGMENT_SEAT_OWNER, trainId, seatType, carriageNumber, segmentIndex));
            } else {
                bitmapKeys.add(String.format(TRAIN_CARRIAGE_SEGMENT_SEAT_BITMAP_BY_SERVICE_DATE, trainId, serviceDateKey, seatType, carriageNumber, segmentIndex));
                ownerKeys.add(String.format(TRAIN_CARRIAGE_SEGMENT_SEAT_OWNER_BY_SERVICE_DATE, trainId, serviceDateKey, seatType, carriageNumber, segmentIndex));
            }
        });
        bitmapKeys.addAll(ownerKeys);
        if (StrUtil.isBlank(serviceDateKey)) {
            bitmapKeys.add(String.format(TRAIN_CARRIAGE_SEAT_LEASE_INDEX_LEGACY, trainId, seatType, carriageNumber));
            bitmapKeys.add(String.format(TRAIN_CARRIAGE_SEAT_LEASE_PAYLOAD_LEGACY, trainId, seatType, carriageNumber));
        } else {
            bitmapKeys.add(String.format(TRAIN_CARRIAGE_SEAT_LEASE_INDEX, trainId, serviceDateKey, seatType, carriageNumber));
            bitmapKeys.add(String.format(TRAIN_CARRIAGE_SEAT_LEASE_PAYLOAD, trainId, serviceDateKey, seatType, carriageNumber));
        }
        return bitmapKeys;
    }

    /**
     * 由租约索引键推导同 hash tag 的元数据键。
     *
     * @param leaseIndexKey 当前租约索引键
     * @return 对应租约元数据 Hash 键
     */
    private String buildLeasePayloadKey(String leaseIndexKey) {
        if (!leaseIndexKey.startsWith(LEASE_INDEX_KEY_PREFIX)) {
            throw new IllegalArgumentException("不是座位租约索引 key");
        }
        return LEASE_PAYLOAD_KEY_PREFIX + leaseIndexKey.substring(LEASE_INDEX_KEY_PREFIX.length());
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
