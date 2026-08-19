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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.RefundTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.SourceEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketChainMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.StationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationRelationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.StationMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationRelationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatClassDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.TicketListDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketOrderItemQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.OrderOperationPreviewRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketItemRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketPreviewRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PayInfoRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderItemCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.OrderCloseRollbackService;
import org.opengoofy.index12306.biz.ticketservice.service.TicketSeatReservationReleaseService;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainServiceDateResolver;
import org.opengoofy.index12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import org.opengoofy.index12306.biz.ticketservice.service.cache.TicketAvailabilityLocalCache;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PurchaseExecutionContext;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PurchaseSeatContext;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PreparedSeatSelection;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TokenResultDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.TrainSeatTypeSelector;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.opengoofy.index12306.biz.ticketservice.toolkit.DateUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.TimeStringComparator;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.cache.toolkit.CacheUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.opengoofy.index12306.framework.starter.log.annotation.ILog;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Timer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_PURCHASE_TICKETS_V2;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_REGION_TRAIN_STATION;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_REGION_TRAIN_STATION_MAPPING;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_TOKEN_BUCKET_ISNULL;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION_MAPPING;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_PRICE;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_RELATION;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;
import static org.opengoofy.index12306.biz.ticketservice.toolkit.DateUtil.convertDateToLocalTime;

/**
 * 车票接口实现
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资�?
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl extends ServiceImpl<TicketMapper, TicketDO> implements TicketService {

    private static final int ORDER_ITEM_PAID_STATUS = 10;

    private final TrainMapper trainMapper;
    private final TrainStationRelationMapper trainStationRelationMapper;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final DistributedCache distributedCache;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final PayRemoteService payRemoteService;
    private final StationMapper stationMapper;
    private final SeatService seatService;
    private final OrderCloseRollbackService orderCloseRollbackService;
    private final TicketSeatReservationReleaseService ticketSeatReservationReleaseService;
    private final TrainSeatTypeSelector trainSeatTypeSelector;
    private final SeatMarginCacheLoader seatMarginCacheLoader;
    private final TicketAvailabilityLocalCache ticketAvailabilityLocalCache;
    private final AbstractChainContext<TicketPageQueryReqDTO> ticketPageQueryAbstractChainContext;
    private final AbstractChainContext<PurchaseExecutionContext> purchaseTicketAbstractChainContext;
    private final AbstractChainContext<RefundTicketReqDTO> refundReqDTOAbstractChainContext;
    private final RedissonClient redissonClient;
    private final ConfigurableEnvironment environment;
    private final TicketAvailabilityTokenBucket ticketAvailabilityTokenBucket;
    private final RedisSeatBitmapService redisSeatBitmapService;
    private final TicketPurchaseMetrics ticketPurchaseMetrics;
    private final TrainServiceDateResolver trainServiceDateResolver;
    private final TransactionTemplate ticketTransactionTemplate;

    @Value("${framework.cache.redis.prefix:}")
    private String cacheRedisPrefix;

    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV1(TicketPageQueryReqDTO requestParam) {
        // 责任链模�?验证城市名称是否存在、不存在加载缓存以及出发日期不能小于当前日期等等
        ticketPageQueryAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 列车查询逻辑较为复杂，详细解析文章查�?https://nageoffer.com/12306/question
        // v1 版本存在严重的性能深渊问题，v2 版本完美的解决了该问题。通过 Jmeter 压测聚合报告得知，性能提升�?300% - 500%+
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
        long count = stationDetails.stream().filter(Objects::isNull).count();
        if (count > 0) {
            RLock lock = redissonClient.getLock(LOCK_REGION_TRAIN_STATION_MAPPING);
            lock.lock();
            try {
                stationDetails = stringRedisTemplate.opsForHash()
                        .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
                count = stationDetails.stream().filter(Objects::isNull).count();
                if (count > 0) {
                    List<StationDO> stationDOList = stationMapper.selectList(Wrappers.emptyWrapper());
                    Map<String, String> regionTrainStationMap = new HashMap<>();
                    stationDOList.forEach(each -> regionTrainStationMap.put(each.getCode(), each.getRegionName()));
                    stringRedisTemplate.opsForHash().putAll(REGION_TRAIN_STATION_MAPPING, regionTrainStationMap);
                    stationDetails = new ArrayList<>();
                    stationDetails.add(regionTrainStationMap.get(requestParam.getFromStation()));
                    stationDetails.add(regionTrainStationMap.get(requestParam.getToStation()));
                }
            } finally {
                lock.unlock();
            }
        }
        List<TicketListDTO> seatResults = new ArrayList<>();
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
        if (MapUtil.isEmpty(regionTrainStationAllMap)) {
            RLock lock = redissonClient.getLock(LOCK_REGION_TRAIN_STATION);
            lock.lock();
            try {
                regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
                if (MapUtil.isEmpty(regionTrainStationAllMap)) {
                    LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                            .eq(TrainStationRelationDO::getStartRegion, stationDetails.get(0))
                            .eq(TrainStationRelationDO::getEndRegion, stationDetails.get(1));
                    List<TrainStationRelationDO> trainStationRelationList = trainStationRelationMapper.selectList(queryWrapper);
                    for (TrainStationRelationDO each : trainStationRelationList) {
                        TrainDO trainDO = distributedCache.safeGet(
                                TRAIN_INFO + each.getTrainId(),
                                TrainDO.class,
                                () -> trainMapper.selectById(each.getTrainId()),
                                ADVANCE_TICKET_DAY,
                                TimeUnit.DAYS);
                        TicketListDTO result = new TicketListDTO();
                        result.setTrainId(String.valueOf(trainDO.getId()));
                        result.setTrainNumber(trainDO.getTrainNumber());
                        result.setDepartureTime(convertDateToLocalTime(each.getDepartureTime(), "HH:mm"));
                        result.setArrivalTime(convertDateToLocalTime(each.getArrivalTime(), "HH:mm"));
                        result.setDuration(DateUtil.calculateHourDifference(each.getDepartureTime(), each.getArrivalTime()));
                        result.setDeparture(each.getDeparture());
                        result.setArrival(each.getArrival());
                        result.setDepartureFlag(each.getDepartureFlag());
                        result.setArrivalFlag(each.getArrivalFlag());
                        result.setTrainType(trainDO.getTrainType());
                        result.setTrainBrand(trainDO.getTrainBrand());
                        if (StrUtil.isNotBlank(trainDO.getTrainTag())) {
                            result.setTrainTags(StrUtil.split(trainDO.getTrainTag(), ","));
                        }
                        long betweenDay = cn.hutool.core.date.DateUtil.betweenDay(each.getDepartureTime(), each.getArrivalTime(), false);
                        result.setDaysArrived((int) betweenDay);
                        result.setSaleStatus(new Date().after(trainDO.getSaleTime()) ? 0 : 1);
                        result.setSaleTime(convertDateToLocalTime(trainDO.getSaleTime(), "MM-dd HH:mm"));
                        seatResults.add(result);
                        regionTrainStationAllMap.put(CacheUtil.buildKey(String.valueOf(each.getTrainId()), each.getDeparture(), each.getArrival()), JSON.toJSONString(result));
                    }
                    stringRedisTemplate.opsForHash().putAll(buildRegionTrainStationHashKey, regionTrainStationAllMap);
                }
            } finally {
                lock.unlock();
            }
        }
        seatResults = CollUtil.isEmpty(seatResults)
                ? regionTrainStationAllMap.values().stream().map(each -> JSON.parseObject(each.toString(), TicketListDTO.class)).toList()
                : seatResults;
        seatResults = seatResults.stream().sorted(new TimeStringComparator()).toList();
        for (TicketListDTO each : seatResults) {
            String trainStationPriceStr = distributedCache.safeGet(
                    String.format(TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()),
                    String.class,
                    () -> {
                        LambdaQueryWrapper<TrainStationPriceDO> trainStationPriceQueryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                                .eq(TrainStationPriceDO::getDeparture, each.getDeparture())
                                .eq(TrainStationPriceDO::getArrival, each.getArrival())
                                .eq(TrainStationPriceDO::getTrainId, each.getTrainId());
                        return JSON.toJSONString(trainStationPriceMapper.selectList(trainStationPriceQueryWrapper));
                    },
                    ADVANCE_TICKET_DAY,
                    TimeUnit.DAYS
            );
            List<TrainStationPriceDO> trainStationPriceDOList = JSON.parseArray(trainStationPriceStr, TrainStationPriceDO.class);
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            trainStationPriceDOList.forEach(item -> {
                String seatType = String.valueOf(item.getSeatType());
                int quantity = queryRemainingTicketQuantity(
                        stringRedisTemplate,
                        String.valueOf(each.getTrainId()),
                        requestParam.getDepartureDate(),
                        item.getDeparture(),
                        item.getArrival(),
                        seatType);
                seatClassList.add(new SeatClassDTO(item.getSeatType(), quantity, new BigDecimal(item.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP), false));
            });
            each.setSeatClassList(seatClassList);
        }
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }


    // 批量查询票信�?
    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV2(TicketPageQueryReqDTO requestParam) {
        // 责任链模�?验证城市名称是否存在、不存在加载缓存以及出发日期不能小于当前日期等等
        ticketPageQueryAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);
        // 获取stringRedisTemplate 实例
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 列车查询逻辑较为复杂，详细解析文章查�?https://nageoffer.com/12306/question
        // v2 版本更符合企业级高并发真实场景的解决方案，完美解决了 v1 版本的性能深渊问题。通过 Jmeter 压测聚合报告可见，性能提升约 300% - 500%+

        // 这里是站点名称映射位地区名称，根据起点和终点获取地区名称
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
                // 一次性取出两个请求，比两次查询性能更好
                // 这里的 multiGet 是一次性取出多个 field 对应的 value，分别对应出发地和目的地的地区名称

        // 也就是把：出发站所属区域到达站所属区域，拼成一个“区域到区域”的 Redis key，用来查这一条线路下有哪些车次
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        // 利用区域到区域的 Redis key，查询这一条线路下有哪些车次
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);

        // 把获取到的车次转换为 TicketListDTO 类型，并排序，排序依据是出发时间，从早到晚
        List<TicketListDTO> seatResults = regionTrainStationAllMap.values().stream()
                .map(each -> JSON.parseObject(each.toString(), TicketListDTO.class))
                .sorted(new TimeStringComparator())
                .toList();

        // 利用车票价格 Redis key，查询车票价格。pipeline 一次性批量查所有车次的价格
        List<String> trainStationPriceKeys = seatResults.stream()
                .map(each -> String.format(cacheRedisPrefix + TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()))
                .toList();
        // 利用车票价格 Redis key，查询车票价格。pipeline 一次性批量查所有车次的价格
        // 使用 pipeline 一次性把命令发送出去，避免多次网络往返，提升效率
        // 结果里面是座位类型和对应价格
        List<Object> trainStationPriceObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            trainStationPriceKeys.forEach(each -> connection.stringCommands().get(each.getBytes()));
            return null;
        });

        // 解析结果构建余票
        // 所有车次、所有座席类型的价格对象，按顺序平铺后的总列表 [G101商务, G101一等, G101二等, D201一等, D201二等]
        List<TrainStationPriceDO> trainStationPriceDOList = new ArrayList<>();
        List<String> trainStationRemainingKeyList = new ArrayList<>();
        // 上面这些价格对象各自对应的“余票 Redis key”
        for (Object each : trainStationPriceObjs) {
            // 把 JSON 数组反序列化为 Java 对象列表
            List<TrainStationPriceDO> trainStationPriceList = JSON.parseArray(each.toString(), TrainStationPriceDO.class);
            // 把所有结果全部平铺在一个列表里
            trainStationPriceDOList.addAll(trainStationPriceList);
            for (TrainStationPriceDO item : trainStationPriceList) {
                // 拼接余票 key：TRAIN_STATION_REMAINING_TICKET:{trainId}_{departure}_{arrival}
                String trainStationRemainingKey = cacheRedisPrefix + TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", item.getTrainId(), item.getDeparture(), item.getArrival());
                // field 是 seatType，value 是余票数
                trainStationRemainingKeyList.add(trainStationRemainingKey);
            }
        }
        // 查询余票
        List<Object> trainStationRemainingObjs = batchQueryRemainingTicketQuantity(
                stringRedisTemplate, trainStationPriceDOList, requestParam.getDepartureDate());
        // 按照车次一个一个把余票装填
        for (TicketListDTO each : seatResults) {
            List<Integer> seatTypesByCode = VehicleTypeEnum.findSeatTypesByCode(each.getTrainType());
            List<Object> remainingTicket = new ArrayList<>(trainStationRemainingObjs.subList(0, seatTypesByCode.size()));
            List<TrainStationPriceDO> trainStationPriceDOSub = new ArrayList<>(trainStationPriceDOList.subList(0, seatTypesByCode.size()));
            trainStationRemainingObjs.subList(0, seatTypesByCode.size()).clear();
            trainStationPriceDOList.subList(0, seatTypesByCode.size()).clear();
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            for (int i = 0; i < trainStationPriceDOSub.size(); i++) {
                TrainStationPriceDO trainStationPriceDO = trainStationPriceDOSub.get(i);
                SeatClassDTO seatClassDTO = SeatClassDTO.builder()
                        .type(trainStationPriceDO.getSeatType())
                        .quantity(Integer.parseInt(remainingTicket.get(i).toString()))
                        .price(new BigDecimal(trainStationPriceDO.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP))
                        .candidate(false)
                        .build();
                seatClassList.add(seatClassDTO);
            }
            each.setSeatClassList(seatClassList);
        }
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }

    // 本地锁缓存，确保每个区间锁独立，避免并发问题
    // 令牌缓存，确保令牌是唯一的，避免重复使用
    private final Cache<String, Object> tokenTicketsRefreshMap = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /**
     * 使用库存令牌执行第二版购票流程，并记录入口校验、令牌获取及完整链路的阶段指标。
     *
     * @param requestParam 购票请求参数
     * @return 新建订单及车票明细
     */
    @ILog
    @Idempotent(
            uniqueKeyPrefix = "index12306-ticket:lock_purchase-tickets:",
            key = "T(org.opengoofy.index12306.framework.starter.bases.ApplicationContextHolder).getBean('environment').getProperty('unique-name', '')"
                    + "+'_'+"
                    + "T(org.opengoofy.index12306.frameworks.starter.user.core.UserContext).getUsername()",
            message = "正在执行下单流程，请稍后...",
            scene = IdempotentSceneEnum.RESTAPI,
            type = IdempotentTypeEnum.SPEL
    )
    @Override
    public TicketPurchaseRespDTO purchaseTicketsV2(PurchaseTicketReqDTO requestParam) {
        Timer.Sample totalTimer = ticketPurchaseMetrics.startStageTimer();
        String failedStage = "request_validation";
        String purchaseResult = "failed";
        try {
            log.debug("ticket_purchase_start trainId={}, boardingDate={}, departure={}, arrival={}, passengerCount={}",
                    requestParam == null ? null : requestParam.getTrainId(), requestParam == null ? null : requestParam.getDepartureDate(),
                    requestParam == null ? null : requestParam.getDeparture(), requestParam == null ? null : requestParam.getArrival(),
                    requestParam == null ? 0 : CollUtil.size(requestParam.getPassengers()));
            Timer.Sample validationTimer = ticketPurchaseMetrics.startStageTimer();
            // 在扣减令牌前确认乘车日期存在，避免无效请求占用库存令牌。
            Timer.Sample purchaseDateTimer = ticketPurchaseMetrics.startStageTimer();
            try {
                validatePurchaseDate(requestParam);
                ticketPurchaseMetrics.recordStage(purchaseDateTimer, "purchase_date_validate", "success");
            } catch (Throwable ex) {
                ticketPurchaseMetrics.recordStage(purchaseDateTimer, "purchase_date_validate", "failed");
                throw ex;
            }
            // 构造上下文前先拒绝缺少定位字段的请求，保留原有客户端错误语义。
            validatePurchaseContextPrerequisites(requestParam);
            // 从启动预热数据一次性构造车次、站点及关联区间快照，后续阶段不再重复读取静态缓存。
            PurchaseExecutionContext purchaseExecutionContext =
                    trainServiceDateResolver.preparePurchaseExecutionContext(requestParam);
            // 始发日期必须在责任链、令牌桶和选座之前统一计算，禁止以用户上车日期直接作为库存维度。
            Timer.Sample serviceDateTimer = ticketPurchaseMetrics.startStageTimer();
            try {
                initializePurchaseServiceDate(purchaseExecutionContext);
                ticketPurchaseMetrics.recordStage(serviceDateTimer, "service_date_resolve", "success");
            } catch (Throwable ex) {
                ticketPurchaseMetrics.recordStage(serviceDateTimer, "service_date_resolve", "failed");
                throw ex;
            }
            // 责任链模式，验证参数、站点及乘客是否允许购买当前车次。
            purchaseTicketAbstractChainContext.handler(
                    TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), purchaseExecutionContext);
            ticketPurchaseMetrics.recordStage(validationTimer, "request_validation", "success");

            failedStage = "inventory_token";
            Timer.Sample tokenTimer = ticketPurchaseMetrics.startStageTimer();
            // 先获取库存令牌进行粗粒度余票校验，后续选座和数据库锁定仍负责最终正确性。
            TokenResultDTO tokenResult = ticketAvailabilityTokenBucket.takeTokenFromBucket(purchaseExecutionContext);
            if (tokenResult.getTokenIsNull()) {
                ticketPurchaseMetrics.recordStage(tokenTimer, "inventory_token", "rejected");
                ticketPurchaseMetrics.recordOutcome("no_ticket");
                purchaseResult = "no_ticket";
                // caffine 存储车次id过期时间十分钟
                Object ifPresentObj = tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId());
                // 如果没有本地缓存，则进行本地缓存，避免由用户取消i订单，数据库有但是缓存没有
                if (ifPresentObj == null) {
                    synchronized (TicketService.class) {
                        if (tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId()) == null) {
                            ifPresentObj = new Object();
                            tokenTicketsRefreshMap.put(requestParam.getTrainId(), ifPresentObj);
                            // 刷新令牌，避免缓存余票与数据库状态长期不一致。
                            tokenIsNullRefreshToken(requestParam, tokenResult);
                        }
                    }
                }
                // 允许少买，但是不允许多卖。
                throw new ServiceException("列车站点已无余票");
            }
            ticketPurchaseMetrics.recordStage(tokenTimer, "inventory_token", "success");

            failedStage = "purchase_execution";
            // 选座、票据落库和 Outbox 受理复用同一静态上下文，订单落库由后台可靠派发。
            TicketPurchaseRespDTO response = executePreparedPurchase(purchaseExecutionContext);
            ticketPurchaseMetrics.recordOutcome("accepted");
            purchaseResult = "accepted";
            log.debug("ticket_purchase_accepted trainId={}, boardingDate={}, departure={}, arrival={}, reservationId={}, status={}",
                    requestParam.getTrainId(), requestParam.getDepartureDate(), requestParam.getDeparture(), requestParam.getArrival(),
                    response.getReservationId(), response.getOrderCreateStatus());
            return response;
        } catch (Throwable ex) {
            String reason = "inventory_token".equals(failedStage) ? "no_ticket" : "system_error";
            ticketPurchaseMetrics.recordFailure(failedStage, reason);
            if (!"inventory_token".equals(failedStage)) {
                ticketPurchaseMetrics.recordOutcome("failed");
            }
            log.warn("ticket_purchase_failed stage={}, trainId={}, boardingDate={}, departure={}, arrival={}",
                    failedStage, requestParam == null ? null : requestParam.getTrainId(),
                    requestParam == null ? null : requestParam.getDepartureDate(),
                    requestParam == null ? null : requestParam.getDeparture(),
                    requestParam == null ? null : requestParam.getArrival(), ex);
            throw ex;
        } finally {
            // 无论购票成功、无票还是异常都记录完整入口耗时，便于比较排队和失败路径。
            ticketPurchaseMetrics.recordStage(totalTimer, "purchase_total", purchaseResult);
        }
    }
    /**
     * 完成本地锁座并可靠受理异步建单任务。
     *
     * @param requestParam 已通过购票校验的请求参数
     * @return 购票受理标识、当前状态及已选座位明细
     */
    @Override
    public TicketPurchaseRespDTO executePurchaseTickets(PurchaseTicketReqDTO requestParam) {
        // 兼容既有内部调用；标准购票入口已经从启动快照传入车次，不会执行本段缓存读取。
        String trainId = requestParam.getTrainId();
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + trainId,
                TrainDO.class,
                () -> trainMapper.selectById(trainId),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        return executePreparedPurchase(new PurchaseExecutionContext(
                requestParam, trainDO, List.of(), List.of()));
    }

    /**
     * 复用单次请求静态上下文完成本地锁座并可靠受理异步建单任务。
     *
     * @param executionContext 已准备车次、站点和受影响区间的购票上下文
     * @return 购票受理标识、当前状态及已选座位明细
     */
    private TicketPurchaseRespDTO executePreparedPurchase(PurchaseExecutionContext executionContext) {
        // 后续本地事务和订单载荷只读取本次请求固定的车次快照。
        PurchaseTicketReqDTO requestParam = executionContext.requestParam();
        TrainDO trainDO = executionContext.train();
        String trainId = requestParam.getTrainId();
        // reservationId 只能由服务端生成，并贯穿 Redis 临时占位、订单映射和后续关闭补偿。
        String reservationId = UUID.randomUUID().toString().replace("-", "");
        // 用户上车日期可能晚于列车始发日期，必须先按基础时刻表换算出运行库存所属的始发日期。
        Date serviceDate = requestParam.getServiceDate();
        if (serviceDate == null) {
            // 事务入口兼容内部调用，但日期换算只读取启动阶段预计算的本地偏移。
            serviceDate = trainServiceDateResolver.resolve(trainDO.getId(), requestParam.getDepartureDate(), requestParam.getDeparture());
            requestParam.setServiceDate(serviceDate);
        }
        // 乘车人和区间价格必须在锁座事务外准备，避免远程调用长期占用数据库连接。
        PurchaseSeatContext purchaseContext = trainSeatTypeSelector.preparePurchaseContext(requestParam);
        // 站点关系属于静态数据，复用分布式缓存并在锁座前完成读取。
        TrainStationRelationDO trainStationRelationDO = distributedCache.safeGet(
                String.format(TRAIN_STATION_RELATION, trainId, requestParam.getDeparture(), requestParam.getArrival()),
                TrainStationRelationDO.class,
                () -> {
                    LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                            .eq(TrainStationRelationDO::getTrainId, trainId)
                            .eq(TrainStationRelationDO::getDeparture, requestParam.getDeparture())
                            .eq(TrainStationRelationDO::getArrival, requestParam.getArrival());
                    return trainStationRelationMapper.selectOne(queryWrapper);
                },
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        if (trainStationRelationDO == null) {
            throw new ServiceException("列车站点关系不存在");
        }
        Date localPreparationServiceDate = serviceDate;
        // 服务端为异步建单生成稳定动作标识，客户端不再传入外部动作标识。
        String actionId = resolvePurchaseActionId(reservationId);
        String orderCreateCommandId = actionId + ":create-order";
        AtomicReference<List<TrainPurchaseTicketRespDTO>> heldTickets = new AtomicReference<>();
        // 先在事务外完成 Redis 乐观临时占位；低余票或基础设施回退场景仍保留原有事务内单通道选座。
        PreparedSeatSelection preparedSeatSelection = trainSeatTypeSelector.prepareOptimisticRedisSeatSelection(
                trainDO.getTrainType(), requestParam, reservationId, purchaseContext);
        if (preparedSeatSelection.optimisticRedisHold()) {
            // Redis owner 已写入，任何后续事务失败都必须由外层按 reservationId 精确释放。
            heldTickets.set(preparedSeatSelection.selectedTickets());
        }
        List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults;
        try {
            // 本地事务只保留数据库确认、车票和 PREPARED/Outbox 写入，远程及 Redis 占位均在事务外完成。
            trainPurchaseTicketResults = ticketTransactionTemplate.execute(status -> preparePurchaseLocally(
                    requestParam, trainDO, localPreparationServiceDate, reservationId,
                    actionId, orderCreateCommandId, purchaseContext, trainStationRelationDO,
                    preparedSeatSelection, heldTickets));
            if (CollUtil.isEmpty(trainPurchaseTicketResults)) {
                throw new ServiceException("本地购票准备结果为空");
            }
        } catch (Throwable ex) {
            // 本地事务失败后数据库变更已回滚，只需按本次 owner 释放已经取得的 Redis 临时占位。
            if (CollUtil.isNotEmpty(heldTickets.get())) {
                releaseRedisSeatHoldAfterFailure(requestParam, heldTickets.get(), "local_prepare");
            }
            throw ex;
        }
        // 本地座位与 Outbox 已原子提交，HTTP 线程直接返回受理结果，不再等待订单服务。
        return TicketPurchaseRespDTO.builder()
                .reservationId(reservationId)
                .orderCreateStatus(TicketSeatReservationReleaseService.ORDER_CREATION_PROCESSING)
                .ticketOrderDetails(buildTicketOrderDetails(trainPurchaseTicketResults))
                .build();
    }

    /**
     * 在单个本地事务中完成数据库锁座、车票写入和 PREPARED reservation 持久化。
     *
     * @param requestParam 已通过校验的购票请求
     * @param trainDO 当前车次
     * @param serviceDate 列车始发日期
     * @param reservationId 本次座位占用标识
     * @param actionId 订单创建动作标识
     * @param commandId 订单创建稳定命令标识
     * @param purchaseContext 锁座前准备完成的乘车人和席别价格快照
     * @param trainStationRelation 当前购票区间时刻信息
     * @param preparedSeatSelection 事务外 Redis 乐观占位结果，回退场景为空标记
     * @param heldTickets 向事务外传递已经取得的 Redis owner，供回滚后精确补偿
     * @return 已锁定并补齐乘客与价格信息的座位明细
     */
    private List<TrainPurchaseTicketRespDTO> preparePurchaseLocally(
            PurchaseTicketReqDTO requestParam, TrainDO trainDO, Date serviceDate, String reservationId,
            String actionId, String commandId, PurchaseSeatContext purchaseContext,
            TrainStationRelationDO trainStationRelation,
            PreparedSeatSelection preparedSeatSelection,
            AtomicReference<List<TrainPurchaseTicketRespDTO>> heldTickets) {
        Timer.Sample seatSelectionTimer = ticketPurchaseMetrics.startStageTimer();
        List<TrainPurchaseTicketRespDTO> selectedTickets;
        try {
            if (preparedSeatSelection.optimisticRedisHold()) {
                // Redis owner 已在事务外完成，本事务只执行数据库 CAS 确认及后续原子持久化。
                selectedTickets = preparedSeatSelection.selectedTickets();
                trainSeatTypeSelector.confirmOptimisticRedisSeatSelection(requestParam, selectedTickets);
            } else {
                // 低余票单通道必须持有区间锁直到数据库确认，继续使用原有事务内路径。
                selectedTickets = trainSeatTypeSelector.select(
                        trainDO.getTrainType(), requestParam, reservationId, purchaseContext);
                heldTickets.set(selectedTickets);
            }
            ticketPurchaseMetrics.recordStage(seatSelectionTimer, "seat_selection", "success");
            log.debug("ticket_seat_selected reservationId={}, trainId={}, boardingDate={}, serviceDate={}, departure={}, arrival={}, seatCount={}",
                    reservationId, requestParam.getTrainId(), requestParam.getDepartureDate(), serviceDate,
                    requestParam.getDeparture(), requestParam.getArrival(), selectedTickets.size());
        } catch (Throwable ex) {
            // 选座器会回收尚未交给主流程的 Redis owner，这里只记录选座失败维度。
            ticketPurchaseMetrics.recordStage(seatSelectionTimer, "seat_selection", "failed");
            ticketPurchaseMetrics.recordFailure("seat_selection", "seat_conflict");
            throw ex;
        }

        // 将已确认座位转换为本地未支付车票，与数据库锁座结果一起提交。
        List<TicketDO> ticketDOList = selectedTickets.stream()
                .map(each -> TicketDO.builder()
                        .username(UserContext.getUsername())
                        .trainId(Long.parseLong(requestParam.getTrainId()))
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .passengerId(each.getPassengerId())
                        .ticketStatus(TicketStatusEnum.UNPAID.getCode())
                        .build())
                .toList();
        Timer.Sample ticketPersistTimer = ticketPurchaseMetrics.startStageTimer();
        try {
            // 车票与锁座处在当前本地事务中，任一步失败都由事务整体回滚。
            saveBatch(ticketDOList);
            ticketPurchaseMetrics.recordStage(ticketPersistTimer, "ticket_persist", "success");
        } catch (Throwable ex) {
            ticketPurchaseMetrics.recordStage(ticketPersistTimer, "ticket_persist", "failed");
            ticketPurchaseMetrics.recordFailure("ticket_persist", "database_error");
            throw ex;
        }

        Timer.Sample reservationPrepareTimer = ticketPurchaseMetrics.startStageTimer();
        try {
            // 购票上下文已经不可变，在事务内生成可独立重放的完整订单请求。
            TicketOrderCreateRemoteReqDTO orderCreateRequest = buildOrderCreateRequest(
                    requestParam, trainDO, trainStationRelation, actionId, commandId, selectedTickets);
            // 同时保存 reservation 和 Outbox，进程中断后仍能重放建单并判断是否释放资源。
            ticketSeatReservationReleaseService.prepareReservation(
                    reservationId, actionId, commandId, UserContext.getUserId(), UserContext.getUsername(),
                    Long.parseLong(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival(),
                    requestParam.getDepartureDate(), serviceDate, selectedTickets, orderCreateRequest);
            ticketPurchaseMetrics.recordStage(reservationPrepareTimer, "reservation_prepare", "success");
            log.debug("ticket_reservation_prepared reservationId={}, commandId={}, trainId={}, serviceDate={}",
                    reservationId, commandId, requestParam.getTrainId(), serviceDate);
        } catch (Throwable ex) {
            ticketPurchaseMetrics.recordStage(reservationPrepareTimer, "reservation_prepare", "failed");
            ticketPurchaseMetrics.recordFailure("reservation_prepare", "database_error");
            throw ex;
        }
        return selectedTickets;
    }

    /**
     * 把锁座结果组装为可由后台独立重放的订单创建请求。
     *
     * @param requestParam 已通过校验的购票请求
     * @param trainDO 当前车次
     * @param trainStationRelation 当前区间时刻
     * @param actionId 订单创建动作标识
     * @param commandId 幂等建单命令标识
     * @param selectedTickets 已确认座位与乘车人信息
     * @return 包含订单主信息与子项的完整请求
     */
    private TicketOrderCreateRemoteReqDTO buildOrderCreateRequest(
            PurchaseTicketReqDTO requestParam,
            TrainDO trainDO,
            TrainStationRelationDO trainStationRelation,
            String actionId,
            String commandId,
            List<TrainPurchaseTicketRespDTO> selectedTickets) {
        // 子项直接使用事务内已确认的座位、票价和乘车人快照，后台重试不再读取外部数据。
        List<TicketOrderItemCreateRemoteReqDTO> orderItems = selectedTickets.stream()
                .map(each -> TicketOrderItemCreateRemoteReqDTO.builder()
                        .amount(each.getAmount())
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .idCard(each.getIdCard())
                        .idType(each.getIdType())
                        .phone(each.getPhone())
                        .seatType(each.getSeatType())
                        .ticketType(each.getUserType())
                        .realName(each.getRealName())
                        .build())
                .toList();
        // 命令标识和用户分片键随载荷持久化，保证后台线程始终路由到同一订单。
        return TicketOrderCreateRemoteReqDTO.builder()
                .actionId(actionId)
                .commandId(commandId)
                .departure(requestParam.getDeparture())
                .arrival(requestParam.getArrival())
                .orderTime(new Date())
                .source(SourceEnum.INTERNET.getCode())
                .trainNumber(trainDO.getTrainNumber())
                .departureTime(trainStationRelation.getDepartureTime())
                .arrivalTime(trainStationRelation.getArrivalTime())
                .ridingDate(requestParam.getDepartureDate())
                .userId(UserContext.getUserId())
                .username(UserContext.getUsername())
                .trainId(Long.parseLong(requestParam.getTrainId()))
                .ticketOrderItems(orderItems)
                .build();
    }

    /**
     * 按当前用户查询异步建单结果。
     *
     * @param reservationId 购票受理标识
     * @return 当前建单状态与真实订单号
     */
    @Override
    public TicketPurchaseStatusRespDTO queryPurchaseStatus(String reservationId) {
        // reservation 服务统一校验用户归属并映射内部生命周期。
        return ticketSeatReservationReleaseService.queryPurchaseStatus(reservationId);
    }

    /**
     * 把已锁定座位转换为首次受理响应中的乘车人明细。
     *
     * @param selectedTickets 已锁定的座位与乘车人快照
     * @return 可立即展示给用户的座位明细
     */
    private List<TicketOrderDetailRespDTO> buildTicketOrderDetails(
            List<TrainPurchaseTicketRespDTO> selectedTickets) {
        // 返回已经由数据库确认的座位，不等待异步订单表落库。
        return selectedTickets.stream()
                .map(each -> TicketOrderDetailRespDTO.builder()
                        .amount(each.getAmount())
                        .carriageNumber(each.getCarriageNumber())
                        .seatNumber(each.getSeatNumber())
                        .idCard(each.getIdCard())
                        .idType(each.getIdType())
                        .seatType(each.getSeatType())
                        .ticketType(each.getUserType())
                        .realName(each.getRealName())
                        .build())
                .toList();
    }

    /**
     * 在本地购票准备失败后释放当前请求的 Redis 临时占座，并记录补偿阶段耗时和失败。
     *
     * @param requestParam 当前购票请求
     * @param tickets 当前请求已经持有的座位
     * @param sourceStage 触发补偿的上游阶段
     */
    private void releaseRedisSeatHoldAfterFailure(PurchaseTicketReqDTO requestParam,
                                                  List<TrainPurchaseTicketRespDTO> tickets,
                                                  String sourceStage) {
        Timer.Sample compensationTimer = ticketPurchaseMetrics.startStageTimer();
        try {
            // 释放动作只使用本次已持有座位携带的 reservationId，避免误释放其他请求的座位。
            redisSeatBitmapService.releaseHeld(requestParam.getTrainId(), requestParam.getServiceDate(),
                    requestParam.getDeparture(), requestParam.getArrival(), tickets);
            ticketPurchaseMetrics.recordStage(compensationTimer, "redis_compensation", "success");
        } catch (Throwable releaseEx) {
            // 原始异常仍需继续抛出，补偿异常通过指标和日志交给后续可靠恢复机制处理。
            ticketPurchaseMetrics.recordStage(compensationTimer, "redis_compensation", "failed");
            ticketPurchaseMetrics.recordFailure("redis_compensation", sourceStage);
            log.error("ticket_redis_compensation_failed sourceStage={}, trainId={}, reservationId={}",
                    sourceStage, requestParam.getTrainId(), tickets.stream()
                            .map(TrainPurchaseTicketRespDTO::getRedisSeatHoldId)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null), releaseEx);
        }
    }

    /**
     * 校验购票请求携带用于订单展示和退改判断的实际乘车日期。
     *
     * @param requestParam 购票请求参数
     */
    private void validatePurchaseDate(PurchaseTicketReqDTO requestParam) {
        // 乘车日期不能再从固定时刻表日期推导，必须由查询和购票链路显式传入。
        if (requestParam == null || requestParam.getDepartureDate() == null) {
            throw new ServiceException("乘车日期不能为空");
        }
    }

    /**
     * 校验构造购票静态上下文所必需的车次和区间字段。
     *
     * @param requestParam 已通过乘车日期校验的购票请求
     */
    private void validatePurchaseContextPrerequisites(PurchaseTicketReqDTO requestParam) {
        // 静态快照必须先定位车次，空标识不能进入数值转换。
        if (StrUtil.isBlank(requestParam.getTrainId())) {
            throw new ClientException("列车标识不能为空");
        }
        // 受影响区间的预计算要求出发站和到达站同时存在。
        if (StrUtil.isBlank(requestParam.getDeparture())) {
            throw new ClientException("出发站点不能为空");
        }
        if (StrUtil.isBlank(requestParam.getArrival())) {
            throw new ClientException("到达站点不能为空");
        }
    }

    /**
     * 根据列车基础时刻表计算并写入本次请求的始发日期库存维度。
     *
     * @param executionContext 已从启动快照准备完成的购票执行上下文
     */
    private void initializePurchaseServiceDate(PurchaseExecutionContext executionContext) {
        // 启动阶段已预计算车次与上车站偏移，热路径不再访问 Redis、数据库或分布式锁。
        PurchaseTicketReqDTO requestParam = executionContext.requestParam();
        requestParam.setServiceDate(trainServiceDateResolver.resolve(
                executionContext.train().getId(), requestParam.getDepartureDate(), requestParam.getDeparture()));
    }

    @Override
    public PayInfoRespDTO getPayInfo(String orderSn) {
        // 支付状态属于订单私有数据，查询支付服务前先验证订单归属。
        requireSelfOrder(orderSn);
        Result<PayInfoRespDTO> payInfoResult = payRemoteService.getPayInfo(orderSn);
        if (!payInfoResult.isSuccess() || payInfoResult.getData() == null) {
            throw new ServiceException("支付单不存在或查询失败");
        }
        return payInfoResult.getData();
    }

    /**
     * 预检查当前用户订单是否允许取消、支付或退票。
     *
     * @param orderSn 订单号
     * @return 由订单服务计算的可操作状态
     */
    @Override
    public OrderOperationPreviewRespDTO previewCancelTicketOrder(String orderSn) {
        org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO order =
                requireSelfOrder(orderSn);

        // 预览只返回稳定状态和安全原因，不触发订单、座位或缓存更新。
        return OrderOperationPreviewRespDTO.builder()
                .orderSn(order.getOrderSn())
                .orderStatus(order.getStatus())
                .canCancel(Boolean.TRUE.equals(order.getCanCancel()))
                .canPay(Boolean.TRUE.equals(order.getCanPay()))
                .canRefund(Boolean.TRUE.equals(order.getCanRefund()))
                .reason(Boolean.TRUE.equals(order.getCanCancel()) ? null : "当前订单状态不允许取消")
                .build();
    }

    @ILog
    @Override
    public void cancelTicketOrder(CancelTicketOrderReqDTO requestParam) {
        // 在任何库存回滚前验证订单属于当前用户且仍允许取消。
        org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO ticketOrderDetail =
                requireSelfOrder(requestParam.getOrderSn());
        if (!Boolean.TRUE.equals(ticketOrderDetail.getCanCancel())) {
            throw new ServiceException("当前订单状态不允许取消");
        }
        Result<Void> cancelOrderResult = ticketOrderRemoteService.cancelTicketOrder(requestParam);
        if (!cancelOrderResult.isSuccess()) {
            throw new ServiceException("取消订单失败");
        }
        try {
            // 用户请求只确认订单关闭；资源释放失败交给持久化命令和 Canal 重投，不把已关闭订单误报为取消失败。
            orderCloseRollbackService.rollback(requestParam.getOrderSn());
        } catch (RuntimeException exception) {
            log.error("[取消订单] 订单号：{} 已关闭，等待异步恢复票务资源", requestParam.getOrderSn(), exception);
        }
    }

    /**
     * 只读预览当前用户按指定范围可退的车票和预计退款金额。
     *
     * @param requestParam 订单号、退款类型和可选子订单范围
     * @return 不产生真实退款的预览结果
     */
    @Override
    public RefundTicketPreviewRespDTO previewTicketRefund(RefundTicketReqDTO requestParam) {
        // 预览与执行使用同一选票和金额计算逻辑，防止确认前后参数含义漂移。
        return buildRefundPlan(requestParam).preview();
    }

    /**
     * 校验当前用户退票范围并以幂等请求标识调用支付退款。
     *
     * @param requestParam 退票请求
     * @return 可追踪退款结果
     */
    @Override
    public RefundTicketRespDTO commonTicketRefund(RefundTicketReqDTO requestParam) {
        RefundPlan plan = buildRefundPlan(requestParam);
        if (!Boolean.TRUE.equals(plan.preview().getRefundable())) {
            throw new ServiceException(plan.preview().getReason());
        }

        // 请求标识优先采用调用方提供值，缺失时按用户、订单和退票范围生成稳定标识。
        String requestId = normalizeRefundRequestId(requestParam, plan.items());
        RefundReqDTO refundReqDTO = new RefundReqDTO();
        // 普通退票直接把稳定退款请求标识传给支付服务。
        refundReqDTO.setRequestId(requestId);
        refundReqDTO.setRefundTypeEnum(RefundTypeEnum.PARTIAL_REFUND.getType().equals(requestParam.getType())
                ? RefundTypeEnum.PARTIAL_REFUND : RefundTypeEnum.FULL_REFUND);
        refundReqDTO.setRefundDetailReqDTOList(plan.items());
        refundReqDTO.setRefundAmount(plan.preview().getRefundAmount());
        refundReqDTO.setOrderSn(requestParam.getOrderSn());
        Result<RefundRespDTO> refundRespDTOResult = payRemoteService.commonRefund(refundReqDTO);
        if (!refundRespDTOResult.isSuccess() || Objects.isNull(refundRespDTOResult.getData())) {
            throw new ServiceException("车票订单退款失败");
        }
        RefundRespDTO payResult = refundRespDTOResult.getData();

        // 票务服务只返回退款跟踪所需字段，不暴露支付渠道内部请求。
        RefundTicketRespDTO response = new RefundTicketRespDTO();
        // 对外返回支付服务确认的退款请求标识，便于用户查询退款结果。
        response.setRequestId(payResult.getRequestId());
        response.setOrderSn(payResult.getOrderSn());
        response.setType(requestParam.getType());
        response.setRefundAmount(payResult.getRefundAmount());
        response.setStatus(payResult.getStatus());
        response.setTradeNo(payResult.getTradeNo());
        return response;
    }

    /**
     * 根据当前座位受理标识生成异步建单动作标识。
     *
     * @param reservationId 服务端座位占用标识
     * @return 可用于订单幂等创建和权威查询的动作标识
     */
    private String resolvePurchaseActionId(String reservationId) {
        // 动作标识只在服务端生成，客户端不能伪造或覆盖当前 reservation 的命令。
        return "purchase-" + reservationId;
    }

    /**
     * 构造退票预览和后续支付退款所需的同一份不可变选票计划。
     *
     * @param requestParam 退票范围
     * @return 退票预览和选中的订单明细
     */
    private RefundPlan buildRefundPlan(RefundTicketReqDTO requestParam) {
        // 责任链先校验订单号、退款类型和部分退票明细是否完整。
        refundReqDTOAbstractChainContext.handler(
                TicketChainMarkEnum.TRAIN_REFUND_TICKET_FILTER.name(), requestParam);
        org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO order =
                requireSelfOrder(requestParam.getOrderSn());
        if (!Boolean.TRUE.equals(order.getCanRefund())) {
            return new RefundPlan(
                    RefundTicketPreviewRespDTO.builder()
                            .orderSn(requestParam.getOrderSn())
                            .type(requestParam.getType())
                            .refundable(false)
                            .refundAmount(0)
                            .items(List.of())
                            .reason("当前订单状态或发车时间不允许退票")
                            .build(),
                    List.of());
        }

        // 全部退款选择尚未退票的明细；部分退款只采用订单服务按记录 ID 返回的明细。
        List<TicketOrderPassengerDetailRespDTO> selectedItems;
        if (RefundTypeEnum.PARTIAL_REFUND.getType().equals(requestParam.getType())) {
            TicketOrderItemQueryReqDTO query = new TicketOrderItemQueryReqDTO();
            query.setOrderSn(requestParam.getOrderSn());
            query.setOrderItemRecordIds(requestParam.getSubOrderRecordIdReqList());
            Result<List<TicketOrderPassengerDetailRespDTO>> selectedResult =
                    ticketOrderRemoteService.queryTicketItemOrderById(query);
            if (!selectedResult.isSuccess() || CollectionUtil.isEmpty(selectedResult.getData())) {
                throw new ServiceException("未找到指定的可退车票");
            }
            selectedItems = selectedResult.getData();
            long requestedCount = requestParam.getSubOrderRecordIdReqList().stream().distinct().count();
            if (selectedItems.size() != requestedCount) {
                throw new ServiceException("部分退票车票范围不完整");
            }
        } else {
            selectedItems = order.getPassengerDetails();
        }
        selectedItems = selectedItems.stream()
                .filter(item -> Objects.equals(item.getStatus(), ORDER_ITEM_PAID_STATUS))
                .toList();
        if (selectedItems.isEmpty()) {
            throw new ServiceException("当前选择中没有可退车票");
        }

        // 退款金额只汇总本次选择且仍处于已支付状态的车票，修复部分退票按全单计算的问题。
        int refundAmount = selectedItems.stream()
                .map(TicketOrderPassengerDetailRespDTO::getAmount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        List<RefundTicketItemRespDTO> previewItems = selectedItems.stream()
                .map(item -> RefundTicketItemRespDTO.builder()
                        .orderItemId(item.getId())
                        .realName(item.getRealName())
                        .seatType(item.getSeatType())
                        .carriageNumber(item.getCarriageNumber())
                        .seatNumber(item.getSeatNumber())
                        .status(item.getStatus())
                        .refundableAmount(item.getAmount())
                        .build())
                .toList();
        RefundTicketPreviewRespDTO preview = RefundTicketPreviewRespDTO.builder()
                .orderSn(requestParam.getOrderSn())
                .type(requestParam.getType())
                .refundable(true)
                .refundAmount(refundAmount)
                .items(previewItems)
                .build();
        return new RefundPlan(preview, selectedItems);
    }

    /**
     * 查询并校验当前登录用户自己的订单。
     *
     * @param orderSn 订单号
     * @return 经过订单服务归属校验的订单详情
     */
    private org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO requireSelfOrder(
            String orderSn) {
        // 终端用户可触发的支付、取消和退票流程统一使用安全订单详情接口。
        Result<org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO> result =
                ticketOrderRemoteService.querySelfTicketOrderByOrderSn(orderSn);
        if (!result.isSuccess() || result.getData() == null) {
            throw new ServiceException("订单不存在或无权访问");
        }
        return result.getData();
    }

    /**
     * 生成或规范化退款请求幂等标识。
     *
     * @param requestParam 原始退票请求
     * @param selectedItems 本次选择的车票明细
     * @return 不超过数据库长度限制的稳定请求标识
     */
    private String normalizeRefundRequestId(
            RefundTicketReqDTO requestParam,
            List<TicketOrderPassengerDetailRespDTO> selectedItems) {
        if (StrUtil.isNotBlank(requestParam.getRequestId())) {
            if (requestParam.getRequestId().trim().length() > 64) {
                throw new ServiceException("退款请求标识过长");
            }
            return requestParam.getRequestId().trim();
        }

        // 未提供请求标识时按用户、订单、类型和有序子订单 ID 生成确定性 UUID。
        String itemIds = selectedItems.stream()
                .map(TicketOrderPassengerDetailRespDTO::getId)
                .sorted()
                .collect(Collectors.joining(","));
        String source = UserContext.getUserId() + "|" + requestParam.getOrderSn()
                + "|" + requestParam.getType() + "|" + itemIds;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    /**
     * @param preview 对用户展示的退票预览
     * @param items 后续真实退款使用的订单明细
     */
    private record RefundPlan(
            RefundTicketPreviewRespDTO preview,
            List<TicketOrderPassengerDetailRespDTO> items) {
    }

    private List<String> buildDepartureStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getDeparture).distinct().collect(Collectors.toList());
    }

    /**
     * 查询用户乘车日期对应始发日的区间余票，展示链路与购票链路使用同一库存键。
     */
    private int queryRemainingTicketQuantity(StringRedisTemplate stringRedisTemplate, String trainId, Date ridingDate,
                                             String departure, String arrival, String seatType) {
        Date serviceDate = resolveServiceDate(trainId, ridingDate, departure);
        Integer localQuantity = ticketAvailabilityLocalCache.getSeatQuantity(trainId, serviceDate, departure, arrival, seatType);
        if (localQuantity != null) {
            return localQuantity;
        }
        String keySuffix = StrUtil.join("_", trainId,
                org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil.format(serviceDate), departure, arrival);
        Object quantityObj = stringRedisTemplate.opsForHash().get(TRAIN_STATION_REMAINING_TICKET + keySuffix, seatType);
        if (quantityObj != null) {
            ticketAvailabilityLocalCache.putSeatQuantity(trainId, serviceDate, departure, arrival, seatType, quantityObj);
            return Integer.parseInt(quantityObj.toString());
        }
        Map<String, String> seatMarginMap = seatMarginCacheLoader.load(trainId, serviceDate, seatType, departure, arrival);
        ticketAvailabilityLocalCache.putRemainingTickets(trainId, serviceDate, departure, arrival, seatMarginMap);
        return Optional.ofNullable(seatMarginMap.get(seatType)).map(Integer::parseInt).orElse(0);
    }

    /**
     * 批量查询用户乘车日期对应始发日的余票，避免查询结果与实际锁座库存不一致。
     */
    private List<Object> batchQueryRemainingTicketQuantity(StringRedisTemplate stringRedisTemplate,
                                                            List<TrainStationPriceDO> trainStationPriceDOList,
                                                            Date ridingDate) {
        if (CollUtil.isEmpty(trainStationPriceDOList)) {
            return Collections.emptyList();
        }
        return trainStationPriceDOList.stream()
                .map(each -> queryRemainingTicketQuantity(
                        stringRedisTemplate,
                        String.valueOf(each.getTrainId()),
                        ridingDate,
                        each.getDeparture(),
                        each.getArrival(),
                        String.valueOf(each.getSeatType())))
                .collect(Collectors.toList());
    }

    /**
     * 根据用户乘车站和日期计算列车实际运行库存所属的始发日期。
     */
    private Date resolveServiceDate(String trainId, Date ridingDate, String departure) {
        // 查询链路与购票链路共用启动阶段生成的不可变偏移表，保证库存日期语义一致。
        return trainServiceDateResolver.resolve(Long.valueOf(trainId), ridingDate, departure);
    }

    private List<String> buildArrivalStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getArrival).distinct().collect(Collectors.toList());
    }

    private List<Integer> buildSeatClassList(List<TicketListDTO> seatResults) {
        Set<Integer> resultSeatClassList = new HashSet<>();
        for (TicketListDTO each : seatResults) {
            for (SeatClassDTO item : each.getSeatClassList()) {
                resultSeatClassList.add(item.getType());
            }
        }
        return resultSeatClassList.stream().toList();
    }

    private List<Integer> buildTrainBrandList(List<TicketListDTO> seatResults) {
        Set<Integer> trainBrandSet = new HashSet<>();
        for (TicketListDTO each : seatResults) {
            if (StrUtil.isNotBlank(each.getTrainBrand())) {
                trainBrandSet.addAll(StrUtil.split(each.getTrainBrand(), ",").stream().map(Integer::parseInt).toList());
            }
        }
        return trainBrandSet.stream().toList();
    }

    private final ScheduledExecutorService tokenIsNullRefreshExecutor = Executors.newScheduledThreadPool(1);

    private void tokenIsNullRefreshToken(PurchaseTicketReqDTO requestParam, TokenResultDTO tokenResult) {
        RLock lock = redissonClient.getLock(String.format(LOCK_TOKEN_BUCKET_ISNULL,
                requestParam.getTrainId() + ':' + org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil.format(requestParam.getServiceDate())));
        if (!lock.tryLock()) {
            return;
        }
        tokenIsNullRefreshExecutor.schedule(() -> {
            try {
                List<Integer> seatTypes = new ArrayList<>();
                Map<Integer, Integer> tokenCountMap = new HashMap<>();
                tokenResult.getTokenIsNullSeatTypeCounts().stream()
                        .map(each -> each.split("_"))
                        .forEach(split -> {
                            int seatType = Integer.parseInt(split[0]);
                            seatTypes.add(seatType);
                            tokenCountMap.put(seatType, Integer.parseInt(split[1]));
                        });
                List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(Long.parseLong(requestParam.getTrainId()),
                        requestParam.getServiceDate(), requestParam.getDeparture(), requestParam.getArrival(), seatTypes);
                for (SeatTypeCountDTO each : seatTypeCountDTOList) {
                    Integer tokenCount = tokenCountMap.get(each.getSeatType());
                    if (tokenCount <= each.getSeatCount()) {
                        ticketAvailabilityTokenBucket.delTokenInBucket(requestParam);
                        break;
                    }
                }
            } finally {
                lock.unlock();
            }
        }, 10, TimeUnit.SECONDS);
    }

}
