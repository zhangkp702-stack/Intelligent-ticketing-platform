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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TokenResultDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil;
import org.opengoofy.index12306.framework.starter.bases.Singleton;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.common.toolkit.Assert;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TICKET_AVAILABILITY_TOKEN_BUCKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TICKET_RESERVATION_TOKEN_ROLLBACK_MARKER;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_CARRIAGE_REMAINING_TICKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 列车车票余量令牌桶，应对海量并发场景下满足并行、限流以及防超卖等场景
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketAvailabilityTokenBucket {

    private final TrainStationService trainStationService;
    private final DistributedCache distributedCache;
    private final RedissonClient redissonClient;
    private final SeatService seatService;
    private final TrainMapper trainMapper;

    // 这里使用了lua脚本，用于原子操作，确保线程安全和性能
    private static final String LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH = "lua/ticket_availability_token_bucket.lua";
    private static final String LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH = "lua/ticket_availability_rollback_token_bucket.lua";

    /**
     * 获取车站间令牌桶中的令牌访问
     * 如果返回 {@link Boolean#TRUE} 代表可以参与接下来的购票下单流程
     * 如果返回 {@link Boolean#FALSE} 代表当前访问出发站点和到达站点令牌已被拿完，无法参与购票下单等逻辑
     *
     * @param requestParam 购票请求参数入参
     * @return 是否获取列车车票余量令牌桶中的令牌返回结果
     */
    public TokenResultDTO takeTokenFromBucket(PurchaseTicketReqDTO requestParam) {
        // 查询当前列车信息
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + requestParam.getTrainId(),
                TrainDO.class,
                () -> trainMapper.selectById(requestParam.getTrainId()),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        // 获取到当前列车所有的可能的区间组合
        List<RouteDTO> routeDTOList = trainStationService
                .listTrainStationRoute(requestParam.getTrainId(), trainDO.getStartStation(), trainDO.getEndStation());
        // 获取redis的stringRedisTemplate
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 定义redis的令牌桶的key
        String tokenBucketHashKey = buildTokenBucketHashKey(requestParam.getTrainId(), requestParam.getServiceDate());
        // 判断redis有没有这个桶，此处判断有没有这个redis的hash是为了后面lua脚本可以扣除
        Boolean hasKey = distributedCache.hasKey(tokenBucketHashKey);
        // 如令牌桶不存在才去初始化
        if (!hasKey) {
            // 获取分布式锁，防止超卖
            RLock lock = redissonClient.getLock(String.format(LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET,
                    buildTokenBucketLockKey(requestParam.getTrainId(), requestParam.getServiceDate())));
            if (!lock.tryLock()) {
                throw new ServiceException("购票异常，请稍候再试");
            }
            try {
                // 二次检验
                Boolean hasKeyTwo = distributedCache.hasKey(tokenBucketHashKey);
                if (!hasKeyTwo) {
                    // 开始初始化，确定有那些座位类型
                    List<Integer> seatTypes = VehicleTypeEnum.findSeatTypesByCode(trainDO.getTrainType());
                    Map<String, String> ticketAvailabilityTokenMap = new HashMap<>();
                    // 遍历所有的区间
                    for (RouteDTO each : routeDTOList) {
                        // 获取该区间可用的座位数量，并按照类型分类统计
                        List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(
                                Long.parseLong(requestParam.getTrainId()),
                                requestParam.getServiceDate(),
                                each.getStartStation(),
                                each.getEndStation(),
                                seatTypes);
                        for (SeatTypeCountDTO eachSeatTypeCountDTO : seatTypeCountDTOList) {
                            // 构建缓存key
                            String buildCacheKey = StrUtil.join("_", each.getStartStation(), each.getEndStation(), eachSeatTypeCountDTO.getSeatType());
                            // 设置对应区间还有座椅类型的票数量
                            ticketAvailabilityTokenMap.put(buildCacheKey, String.valueOf(eachSeatTypeCountDTO.getSeatCount()));
                        }
                    }
                    // 把缓存数据设置到redis
                    stringRedisTemplate.opsForHash().putAll(tokenBucketHashKey, ticketAvailabilityTokenMap);
                }
            } finally {
                lock.unlock();
            }
        }
        // 加载Lua脚本
        DefaultRedisScript<String> actual = Singleton.get(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH, () -> {
            DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(String.class);
            return redisScript;
        });
        Assert.notNull(actual);
        // 统计每一种座位的票数量
        Map<Integer, Long> seatTypeCountMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType, Collectors.counting()));
        // 转换为json数组
        JSONArray seatTypeCountArray = seatTypeCountMap.entrySet().stream()
                .map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("seatType", String.valueOf(entry.getKey()));
                    jsonObject.put("count", String.valueOf(entry.getValue()));
                    return jsonObject;
                })
                .collect(Collectors.toCollection(JSONArray::new));
        // 获取所有会受到影响的站点区间
        List<RouteDTO> takeoutRouteDTOList = trainStationService
                .listTakeoutTrainStationRoute(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        // 把当前请求区间作为key传递给lua
        String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());
        // 此处调用execute把lua脚本发送给redis执行
        String resultStr = stringRedisTemplate.execute(
                actual,
                Lists.newArrayList(tokenBucketHashKey, luaScriptKey),
                JSON.toJSONString(seatTypeCountArray),
                JSON.toJSONString(takeoutRouteDTOList));
        TokenResultDTO result = JSON.parseObject(resultStr, TokenResultDTO.class);
        return result == null
                ? TokenResultDTO.builder().tokenIsNull(Boolean.TRUE).build()
                : result;
    }

    /**
     * 回滚列车余量令牌，一般为订单取消或长时间未支付触发
     *
     * @param requestParam 回滚列车余量令牌入参
     */
    public void rollbackInBucket(TicketOrderDetailRespDTO requestParam) {
        DefaultRedisScript<Long> actual = Singleton.get(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
        Assert.notNull(actual);
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = requestParam.getPassengerDetails();
        Map<Integer, Long> seatTypeCountMap = passengerDetails.stream()
                .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType, Collectors.counting()));
        JSONArray seatTypeCountArray = seatTypeCountMap.entrySet().stream()
                .map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("seatType", String.valueOf(entry.getKey()));
                    jsonObject.put("count", String.valueOf(entry.getValue()));
                    return jsonObject;
                })
                .collect(Collectors.toCollection(JSONArray::new));
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        String actualHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
        String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());
        List<RouteDTO> takeoutRouteDTOList = trainStationService.listTakeoutTrainStationRoute(String.valueOf(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival());
        Long result = stringRedisTemplate.execute(actual, Lists.newArrayList(actualHashKey, luaScriptKey), JSON.toJSONString(seatTypeCountArray), JSON.toJSONString(takeoutRouteDTOList));
        if (result == null || !Objects.equals(result, 0L)) {
            log.error("回滚列车余票令牌失败，订单信息：{}", JSON.toJSONString(requestParam));
            throw new ServiceException("回滚列车余票令牌失败");
        }
    }

    /**
     * 按稳定 reservationId 原子回滚令牌桶和车厢摘要缓存，重复调用不会再次增加余票。
     *
     * @param requestParam 已关闭订单的完整车票信息
     * @param rollbackKey reservationId 等稳定幂等键
     * @param includeRemainingTicketCache 是否同时恢复全局区间余票缓存
     * @return 本次实际执行缓存回滚时返回 true，已执行过时返回 false
     */
    public boolean rollbackInBucketIfNecessary(
            TicketOrderDetailRespDTO requestParam,
            String rollbackKey,
            boolean includeRemainingTicketCache) {
        return rollbackInBucketIfNecessary(requestParam, null, rollbackKey, includeRemainingTicketCache);
    }

    /**
     * 按预订所属的列车始发日期回滚令牌和余票摘要。
     *
     * @param requestParam 已关闭订单的完整车票信息
     * @param serviceDate 列车始发日期；历史预订为空时回退旧键
     * @param rollbackKey reservationId 等稳定幂等键
     * @param includeRemainingTicketCache 是否同时恢复区间余票缓存
     * @return 本次实际回滚时返回 true，已回滚时返回 false
     */
    public boolean rollbackInBucketIfNecessary(
            TicketOrderDetailRespDTO requestParam,
            Date serviceDate,
            String rollbackKey,
            boolean includeRemainingTicketCache) {
        // 复用令牌桶脚本，在写入订单去重标记后一次性完成令牌和车厢摘要增量。
        DefaultRedisScript<Long> actual = Singleton.get(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
        Assert.notNull(actual);

        List<TicketOrderPassengerDetailRespDTO> passengerDetails = requestParam.getPassengerDetails();
        Map<Integer, Long> seatTypeCountMap = passengerDetails.stream()
                .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType, Collectors.counting()));
        JSONArray seatTypeCountArray = seatTypeCountMap.entrySet().stream()
                .map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("seatType", String.valueOf(entry.getKey()));
                    jsonObject.put("count", String.valueOf(entry.getValue()));
                    return jsonObject;
                })
                .collect(Collectors.toCollection(JSONArray::new));
        JSONArray carriageSummaryArray = passengerDetails.stream()
                .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType,
                        Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getCarriageNumber, Collectors.counting())))
                .entrySet().stream()
                .flatMap(seatTypeEntry -> seatTypeEntry.getValue().entrySet().stream().map(carriageEntry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("summaryKey", TRAIN_STATION_CARRIAGE_REMAINING_TICKET + ServiceDateKeyUtil.buildKey(
                            String.valueOf(requestParam.getTrainId()), serviceDate, requestParam.getDeparture(),
                            requestParam.getArrival(), String.valueOf(seatTypeEntry.getKey())));
                    jsonObject.put("carriageNumber", carriageEntry.getKey());
                    jsonObject.put("count", String.valueOf(carriageEntry.getValue()));
                    return jsonObject;
                }))
                .collect(Collectors.toCollection(JSONArray::new));

        // reservation 标记与所有缓存增量在同一 Lua 脚本内提交，进程崩溃后重试不会出现半次回滚。
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        String actualHashKey = buildTokenBucketHashKey(requestParam.getTrainId(), serviceDate);
        String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());
        List<RouteDTO> takeoutRouteDTOList = trainStationService.listTakeoutTrainStationRoute(
                String.valueOf(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival());
        JSONArray remainingTicketArray = takeoutRouteDTOList.stream()
                .flatMap(route -> seatTypeCountMap.entrySet().stream().map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("remainingKey", TRAIN_STATION_REMAINING_TICKET + ServiceDateKeyUtil.buildKey(
                            String.valueOf(requestParam.getTrainId()), serviceDate, route.getStartStation(), route.getEndStation()));
                    jsonObject.put("seatType", String.valueOf(entry.getKey()));
                    jsonObject.put("count", String.valueOf(entry.getValue()));
                    return jsonObject;
                }))
                .collect(Collectors.toCollection(JSONArray::new));
        Long result = stringRedisTemplate.execute(actual, Lists.newArrayList(
                        actualHashKey, luaScriptKey, String.format(TICKET_RESERVATION_TOKEN_ROLLBACK_MARKER, rollbackKey)),
                JSON.toJSONString(seatTypeCountArray), JSON.toJSONString(takeoutRouteDTOList),
                JSON.toJSONString(carriageSummaryArray),
                includeRemainingTicketCache ? JSON.toJSONString(remainingTicketArray) : "");
        if (result == null || (result != 0L && result != 1L)) {
            log.error("reservation 回滚列车余票缓存失败，回滚键：{}", rollbackKey);
            throw new ServiceException("订单关闭回滚列车余票缓存失败");
        }
        return Objects.equals(result, 0L);
    }

    /**
     * 删除令牌，一般在令牌与数据库不一致情况下触发
     *
     * @param requestParam 删除令牌容器参数
     */
    public void delTokenInBucket(PurchaseTicketReqDTO requestParam) {
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        String tokenBucketHashKey = buildTokenBucketHashKey(requestParam.getTrainId(), requestParam.getServiceDate());
        stringRedisTemplate.delete(tokenBucketHashKey);
    }

    /**
     * 构造按始发日期隔离的令牌桶键，并兼容未保存始发日期的历史订单。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @return 令牌桶 Redis 键
     */
    private String buildTokenBucketHashKey(Object trainId, Date serviceDate) {
        // 历史订单没有始发日期时必须继续命中旧桶，避免异步释放向新空桶错误归还令牌。
        return serviceDate == null
                ? TICKET_AVAILABILITY_TOKEN_BUCKET + trainId
                : TICKET_AVAILABILITY_TOKEN_BUCKET + trainId + ':' + ServiceDateKeyUtil.format(serviceDate);
    }

    /**
     * 构造令牌桶初始化锁的业务键。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @return 锁模板使用的业务键
     */
    private String buildTokenBucketLockKey(Object trainId, Date serviceDate) {
        // 新订单始终携带始发日期；保留空日期分支便于历史数据修复时复用旧锁。
        return serviceDate == null
                ? String.valueOf(trainId)
                : trainId + ":" + ServiceDateKeyUtil.format(serviceDate);
    }

    public void putTokenInBucket() {

    }

    public void initializeTokens() {

    }
}
