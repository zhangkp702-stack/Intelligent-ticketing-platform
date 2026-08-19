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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainSeatOccupancyMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationSegmentBitmapUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
    private final DistributedCache distributedCache;
    private final Cache<String, Boolean> readyServiceDateInventory = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();

    /**
     * 校验日期运行库存已经完整生成，购票请求只读就绪状态而不再同步复制全部座位。
     *
     * @param trainId 列车标识
     * @param serviceDate 始发日期；为空时沿用旧库存兼容路径
     */
    @Override
    public void validateServiceDateInventoryReady(String trainId, Date serviceDate) {
        if (serviceDate == null) {
            return;
        }
        String inventoryKey = trainId + ':' + ServiceDateKeyUtil.format(serviceDate);
        if (readyServiceDateInventory.getIfPresent(inventoryKey) != null) {
            return;
        }
        // 缓存未命中时只执行完整性查询；生成工作必须由车次发布或压测预热流程提前完成。
        boolean inventoryReady = trainSeatOccupancyMapper.isServiceDateInventoryReady(Long.parseLong(trainId), serviceDate);
        if (!inventoryReady) {
            throw new ServiceException("当前车次日期库存未就绪，请先执行库存预生成");
        }
        // 仅缓存成功状态，预热尚未完成的请求可在数据补齐后立即重新校验。
        readyServiceDateInventory.put(inventoryKey, Boolean.TRUE);
    }

    @Override
    public List<String> listAvailableSeat(String trainId, Date serviceDate, String carriageNumber, Integer seatType, String departure, String arrival) {
        // 获取当前列车的位图
        long requestMask = buildRequestMask(trainId, departure, arrival);
        // 去数据库中找所有和当前指令与运算之后位0的座位
        validateServiceDateInventoryReady(trainId, serviceDate);
        List<SeatDO> availableSeats = serviceDate == null
                ? seatMapper.listAvailableSeatByCarriage(Long.parseLong(trainId), carriageNumber, seatType, requestMask, 1000)
                : trainSeatOccupancyMapper.listAvailableSeatByCarriage(Long.parseLong(trainId), serviceDate, carriageNumber, seatType, requestMask, 1000);
        return availableSeats.stream().map(SeatDO::getSeatNumber).collect(Collectors.toList());
    }

    @Override
    public List<Integer> listSeatRemainingTicket(String trainId, Date serviceDate, String departure, String arrival, List<String> trainCarriageList) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        validateServiceDateInventoryReady(trainId, serviceDate);
        if (serviceDate == null) {
            return seatMapper.listSeatRemainingTicket(Long.parseLong(trainId), requestMask, trainCarriageList);
        }
        return trainSeatOccupancyMapper.listSeatRemainingTicket(Long.parseLong(trainId), serviceDate, requestMask, trainCarriageList);
    }

    @Override
    public List<String> listUsableCarriageNumber(String trainId, Date serviceDate, Integer carriageType, String departure, String arrival) {
        long requestMask = buildRequestMask(trainId, departure, arrival);
        validateServiceDateInventoryReady(trainId, serviceDate);
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
        validateServiceDateInventoryReady(trainId, serviceDate);
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
        validateServiceDateInventoryReady(String.valueOf(trainId), serviceDate);
        return serviceDate == null
                ? seatMapper.listSeatTypeCount(trainId, requestMask, seatTypes)
                : trainSeatOccupancyMapper.listSeatTypeCount(trainId, serviceDate, requestMask, seatTypes);
    }

    /**
     * 批量加载本次选择的静态座位，并以数据库区间位图条件更新确认座位占用。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param tickets 已由选座策略确定的座位
     * @return 全部座位确认成功返回 true，整批冲突或座位映射缺失返回 false
     * @throws ServiceException 批量 CAS 仅更新部分座位时抛出，要求外层事务整体回滚
    */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean tryLockSeat(String trainId, Date serviceDate, String departure, String arrival, List<TrainPurchaseTicketRespDTO> tickets) {
        // 空候选不能生成 IN 条件，更不能退化为无条件扫描或更新。
        if (tickets == null || tickets.isEmpty()) {
            return false;
        }
        long requestMask = buildRequestMask(trainId, departure, arrival);
        Long trainIdLong = Long.parseLong(trainId);
        validateServiceDateInventoryReady(trainId, serviceDate);
        List<TrainPurchaseTicketRespDTO> sortedTickets = tickets.stream()
                .sorted(Comparator.comparing(each -> buildSeatIdentity(
                        each.getCarriageNumber(), each.getSeatNumber(), each.getSeatType())))
                .toList();
        // 一次查询取得全部静态座位主键，避免按乘客逐座位访问 t_seat。
        List<SeatDO> selectedSeats = seatMapper.selectList(Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, trainIdLong)
                .in(SeatDO::getCarriageNumber, sortedTickets.stream()
                        .map(TrainPurchaseTicketRespDTO::getCarriageNumber).distinct().toList())
                .in(SeatDO::getSeatNumber, sortedTickets.stream()
                        .map(TrainPurchaseTicketRespDTO::getSeatNumber).distinct().toList())
                .in(SeatDO::getSeatType, sortedTickets.stream()
                        .map(TrainPurchaseTicketRespDTO::getSeatType).distinct().toList()));
        Map<String, SeatDO> seatByIdentity = selectedSeats.stream().collect(Collectors.toMap(
                each -> buildSeatIdentity(each.getCarriageNumber(), each.getSeatNumber(), each.getSeatType()),
                each -> each,
                (left, right) -> left));
        List<Long> selectedSeatIds = new ArrayList<>(sortedTickets.size());
        for (TrainPurchaseTicketRespDTO ticket : sortedTickets) {
            SeatDO seat = seatByIdentity.get(buildSeatIdentity(
                    ticket.getCarriageNumber(), ticket.getSeatNumber(), ticket.getSeatType()));
            if (seat == null) {
                return false;
            }
            // 先完成全部静态座位映射，再执行批量更新，避免输入缺失时产生部分数据库变更。
            selectedSeatIds.add(seat.getId());
        }
        if (selectedSeatIds.stream().distinct().count() != sortedTickets.size()) {
            return false;
        }
        // Redis owner 已经完成跨实例排他；数据库以单条批量 CAS 作为最终一致性确认。
        int updated = tryLockSeatsByBitmap(trainIdLong, serviceDate, selectedSeatIds, requestMask);
        if (updated == selectedSeatIds.size()) {
            return true;
        }
        if (updated == 0) {
            return false;
        }
        // 部分成功不能在当前事务内继续换座，否则会携带本批次脏占位；抛错交给外层事务整体回滚。
        throw new ServiceException("座位批量确认发生部分冲突，请稍后重试");
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
     * 构造静态座位唯一标识，用于批量查询结果与选座结果的内存关联。
     *
     * @param carriageNumber 车厢编号
     * @param seatNumber 座位编号
     * @param seatType 席别类型
     * @return 当前列车内稳定的座位标识
     */
    private String buildSeatIdentity(String carriageNumber, String seatNumber, Integer seatType) {
        // t_seat 唯一索引包含列车、车厢、座位号和席别，列车已由外层查询固定。
        return carriageNumber + '#' + seatNumber + '#' + seatType;
    }

    /**
     * 构造车厢余票摘要的日期隔离键后缀。
     */
    private String buildKeySuffix(String trainId, Date serviceDate, String departure, String arrival, Integer seatType) {
        return String.join("_", trainId, ServiceDateKeyUtil.format(serviceDate), departure, arrival, String.valueOf(seatType));
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
     * 以区间未占用条件批量确认座位，历史预订记录仍保留旧表回退路径。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param seatIds 静态座位标识集合
     * @param requestMask 本次乘车区间位图
     * @return 数据库受影响行数
     */
    private int tryLockSeatsByBitmap(Long trainId, Date serviceDate, List<Long> seatIds, long requestMask) {
        if (serviceDate == null) {
            return seatMapper.tryLockSeatsByBitmap(seatIds, requestMask);
        }
        // SQL 对整批座位直接使用区间未占用条件，不需要逐座读取版本号。
        return trainSeatOccupancyMapper.tryLockSeatsByBitmap(trainId, serviceDate, seatIds, requestMask);
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

}
