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

package org.opengoofy.index12306.biz.ticketservice.service;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PurchaseExecutionContext;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationCalculateUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_STOPOVER_DETAIL;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;

/**
 * 根据用户上车日期和基础时刻表计算列车始发日期。
 *
 * <p>运行库存必须使用始发日期，而不是中间站乘客的上车日期，才能把同一车次不同开行日隔离开。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrainServiceDateResolver implements ApplicationRunner {

    private static final ZoneId CHINA_ZONE_ID = ZoneId.of("Asia/Shanghai");

    private final TrainMapper trainMapper;
    private final TrainStationMapper trainStationMapper;
    private final DistributedCache distributedCache;

    private volatile Map<TrainDepartureKey, Integer> departureDayOffsets = Map.of();
    private volatile Map<Long, TrainDO> trainSnapshots = Map.of();
    private volatile Map<Long, List<String>> stationNameSnapshots = Map.of();

    /**
     * 在服务接收流量前预计算全部车次站点的跨天偏移，并预热共享静态缓存。
     *
     * @param args Spring Boot 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        long startMillis = System.currentTimeMillis();
        // 一次性读取静态时刻表，正式购票不再为始发日期换算访问 Redis 或数据库。
        List<TrainDO> trains = trainMapper.selectList(null);
        List<TrainStationDO> stations = trainStationMapper.selectList(null);
        if (trains == null || trains.isEmpty() || stations == null || stations.isEmpty()) {
            throw new IllegalStateException("车次或经停站数据为空，无法预热始发日期偏移");
        }

        // 按车次组织基础信息和有序经停站，为本地偏移及 Redis 静态缓存复用同一份数据库快照。
        Map<Long, TrainDO> trainById = new HashMap<>(trains.size());
        trains.forEach(train -> {
            if (train.getId() != null && train.getDepartureTime() != null) {
                trainById.put(train.getId(), train);
            }
        });
        Map<Long, List<TrainStationDO>> stationsByTrain = new HashMap<>();
        stations.forEach(station -> stationsByTrain
                .computeIfAbsent(station.getTrainId(), ignored -> new ArrayList<>())
                .add(station));

        Map<TrainDepartureKey, Integer> warmedOffsets = new HashMap<>(stations.size());
        Map<Long, List<String>> warmedStationNames = new HashMap<>(stationsByTrain.size());
        stationsByTrain.forEach((trainId, trainStations) -> {
            TrainDO train = trainById.get(trainId);
            if (train == null) {
                throw new IllegalStateException("经停站缺少对应车次基础信息: " + trainId);
            }
            // 同一车次的经停站按既有序号排序，避免预热后站点方向校验依赖数据库返回顺序。
            trainStations.sort((left, right) -> compareSequence(left.getSequence(), right.getSequence()));
            for (TrainStationDO station : trainStations) {
                TrainDepartureKey key = new TrainDepartureKey(trainId, station.getDeparture());
                int offset = calculateDepartureDayOffset(train, station);
                Integer previous = warmedOffsets.putIfAbsent(key, offset);
                if (previous != null && previous != offset) {
                    throw new IllegalStateException("同一车次站点存在冲突的跨天偏移: " + trainId + ':' + station.getDeparture());
                }
            }
            // 有序站名同时作为购票责任链和令牌桶的本地只读数据源，避免重复读取并解析 Redis。
            warmedStationNames.put(trainId, trainStations.stream()
                    .map(TrainStationDO::getDeparture)
                    .toList());
            // 经停站列表提前写入 Redis，责任链首个请求不再通过分布式锁回源数据库。
            distributedCache.put(TRAIN_STATION_STOPOVER_DETAIL + trainId,
                    JSON.toJSONString(trainStations), ADVANCE_TICKET_DAY, TimeUnit.DAYS);
        });
        trains.forEach(train -> {
            // 车次基础信息提前写入 Redis，车次校验和后续订单参数组装直接命中共享缓存。
            distributedCache.put(TRAIN_INFO + train.getId(), train, ADVANCE_TICKET_DAY, TimeUnit.DAYS);
        });
        // 最后一次性发布不可变快照，避免请求线程观察到只完成一部分的预热状态。
        departureDayOffsets = Map.copyOf(warmedOffsets);
        trainSnapshots = Map.copyOf(trainById);
        stationNameSnapshots = Map.copyOf(warmedStationNames);
        log.info("ticket_static_data_warmup_completed trainCount={}, stationCount={}, offsetCount={}, elapsedMillis={}",
                trains.size(), stations.size(), departureDayOffsets.size(), System.currentTimeMillis() - startMillis);
    }

    /**
     * 计算一次购票对应列车从始发站出发的日期。
     *
     * @param trainId 列车标识
     * @param ridingDate 用户从指定出发站上车的日期
     * @param departure 用户选择的出发站
     * @return 与运行库存绑定的列车始发日期
     */
    public Date resolve(Long trainId, Date ridingDate, String departure) {
        // 请求日期、车次和出发站是定位预热偏移的必要输入，缺失时不能继续使用默认日期。
        if (trainId == null || ridingDate == null || departure == null || departure.isBlank()) {
            throw new ServiceException("计算列车始发日期失败");
        }
        // 偏移在启动阶段已经完成数据库计算，热路径只进行本地不可变 Map 查询和日期减法。
        Integer departureDayOffset = departureDayOffsets.get(new TrainDepartureKey(trainId, departure));
        if (departureDayOffset == null) {
            throw new ServiceException("列车出发站跨天偏移未预热");
        }
        LocalDate serviceDate = toLocalDate(ridingDate).minusDays(departureDayOffset);
        return Date.from(serviceDate.atStartOfDay(CHINA_ZONE_ID).toInstant());
    }

    /**
     * 从启动预热快照构造单次购票的不可变静态上下文。
     *
     * @param requestParam 已包含车次和乘车区间的购票请求
     * @return 可在责任链、令牌桶和锁座阶段复用的静态上下文
     */
    public PurchaseExecutionContext preparePurchaseExecutionContext(PurchaseTicketReqDTO requestParam) {
        // 车次标识先转换为数值，避免格式错误在后续日期换算中暴露为无语义异常。
        Long trainId;
        try {
            trainId = Long.valueOf(requestParam.getTrainId());
        } catch (RuntimeException ex) {
            throw new ClientException("列车标识格式错误");
        }
        // 车次和有序站点必须来自同一轮启动快照，禁止热路径再次访问 Redis 或数据库。
        TrainDO train = trainSnapshots.get(trainId);
        List<String> stationNames = stationNameSnapshots.get(trainId);
        if (train == null || stationNames == null || stationNames.isEmpty()) {
            throw new ClientException("请检查车次是否存在");
        }
        // Lua 扣减所需的关联区间只计算一次，令牌桶和后续阶段共享同一结果。
        return new PurchaseExecutionContext(
                requestParam,
                train,
                stationNames,
                StationCalculateUtil.takeoutStation(
                        stationNames, requestParam.getDeparture(), requestParam.getArrival()));
    }

    /**
     * 根据静态时刻表计算一个经停站相对始发站跨越的自然日数。
     *
     * @param train 列车基础时刻信息
     * @param station 经停站时刻信息
     * @return 非负的自然日偏移
     */
    private int calculateDepartureDayOffset(TrainDO train, TrainStationDO station) {
        // 预热阶段集中拒绝残缺或逆序时刻表，避免错误偏移进入所有后续购票请求。
        if (station.getDeparture() == null || station.getDeparture().isBlank() || station.getDepartureTime() == null) {
            throw new IllegalStateException("列车经停站时刻不完整: " + train.getId());
        }
        long offset = ChronoUnit.DAYS.between(toLocalDate(train.getDepartureTime()),
                toLocalDate(station.getDepartureTime()));
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new IllegalStateException("列车出发站跨天偏移异常: " + train.getId() + ':' + station.getDeparture());
        }
        return (int) offset;
    }

    /**
     * 按数值优先规则比较旧版字符串站序，保证预热缓存中的经停站方向稳定。
     *
     * @param left 左侧站序
     * @param right 右侧站序
     * @return 负数、零或正数
     */
    private int compareSequence(String left, String right) {
        // 现有数据通常为数字字符串；异常格式退回字符串比较，避免启动预热因展示字段格式失败。
        try {
            return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
        } catch (RuntimeException ignored) {
            return String.valueOf(left).compareTo(String.valueOf(right));
        }
    }

    /**
     * 将旧版时刻表的 Date 按中国时区转换为自然日。
     *
     * @param value 数据库存储的日期时间
     * @return 对应的中国时区自然日
     */
    private LocalDate toLocalDate(Date value) {
        // 显式指定业务时区，避免部署主机时区变化导致库存日期被错误分片。
        return value.toInstant().atZone(CHINA_ZONE_ID).toLocalDate();
    }

    /** 车次和上车站共同确定唯一的跨天偏移。 */
    private record TrainDepartureKey(Long trainId, String departure) {
    }
}
