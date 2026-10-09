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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.inventory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil;
import org.opengoofy.index12306.framework.starter.bases.Singleton;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TICKET_INVENTORY_ROLLBACK_MARKER;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_CARRIAGE_REMAINING_TICKET;

/**
 * 统一库存关闭回滚组件，负责库存回补和车厢摘要缓存的幂等恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketInventoryRollbackService {

    private static final String LUA_CARRIAGE_SUMMARY_ROLLBACK_PATH =
            "lua/ticket_inventory_carriage_summary_rollback.lua";

    private final TrainStationService trainStationService;
    private final DistributedCache distributedCache;
    private final TicketInventoryService ticketInventoryService;

    /**
     * 回滚已关闭订单的统一库存，并在同一幂等键下恢复车厢摘要。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 已关闭订单统一使用的席别
     * @param seatCount 已关闭订单的乘车人数
     * @param tickets reservation 保存的座位快照，仅用于恢复车厢摘要
     * @param rollbackKey reservationId 等稳定幂等标识
     * @return 首次执行摘要回滚时返回 true，重复调用返回 false
     */
    public boolean rollbackIfNecessary(String trainId, Date serviceDate, String departure, String arrival,
                                       Integer seatType, Integer seatCount,
                                       List<TrainPurchaseTicketRespDTO> tickets, String rollbackKey) {
        if (serviceDate == null) {
            throw new ServiceException("订单缺少列车始发日期，无法回滚统一库存");
        }
        if (seatType == null || seatCount == null || seatCount <= 0 || tickets == null || tickets.size() != seatCount) {
            throw new ServiceException("座位占用记录的席别或人数与座位快照不一致");
        }
        // 统一库存脚本先按 reservationId 回补，席别和人数只读取 reservation 订单级字段。
        List<RouteDTO> affectedRoutes = trainStationService.listTakeoutTrainStationRoute(
                trainId, departure, arrival);
        ticketInventoryService.rollbackInUnified(
                trainId, serviceDate, affectedRoutes, seatType, seatCount, rollbackKey);

        // 车厢摘要只按车厢聚合，统一席别由 reservation 传入，覆盖库存回补后进程中断的重试窗口。
        JSONArray carriageSummaryArray = tickets.stream()
                .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getCarriageNumber, Collectors.counting()))
                .entrySet().stream()
                .map(carriageEntry -> {
                    com.alibaba.fastjson2.JSONObject jsonObject = new com.alibaba.fastjson2.JSONObject();
                    jsonObject.put("summaryKey", TRAIN_STATION_CARRIAGE_REMAINING_TICKET + ServiceDateKeyUtil.buildKey(
                            trainId, serviceDate, departure, arrival, String.valueOf(seatType)));
                    jsonObject.put("carriageNumber", carriageEntry.getKey());
                    jsonObject.put("count", String.valueOf(carriageEntry.getValue()));
                    return jsonObject;
                })
                .collect(Collectors.toCollection(JSONArray::new));
        String markerKey = String.format(TICKET_INVENTORY_ROLLBACK_MARKER, rollbackKey);
        Long result = ((StringRedisTemplate) distributedCache.getInstance()).execute(
                getRollbackScript(), List.of(markerKey), JSON.toJSONString(carriageSummaryArray));
        if (result == null || (result != 0L && result != 1L)) {
            log.error("统一库存车厢摘要回滚失败 rollbackKey={}", rollbackKey);
            throw new ServiceException("统一库存车厢摘要回滚失败");
        }
        return Objects.equals(result, 0L);
    }

    /**
     * 回滚尚未产生具体座位和车厢摘要的初始化预订库存。
     *
     * @param trainId 列车标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 订单统一席别
     * @param seatCount 订单乘车人数
     * @param rollbackKey reservationId 幂等标识
     */
    public void rollbackInitialIfNecessary(String trainId, Date serviceDate, String departure, String arrival,
                                           Integer seatType, Integer seatCount, String rollbackKey) {
        if (serviceDate == null) {
            throw new ServiceException("初始化预订缺少列车始发日期，无法回滚统一库存");
        }
        if (seatType == null || seatCount == null || seatCount <= 0) {
            throw new ServiceException("初始化预订的席别或人数无效");
        }
        // INIT 尚未选择具体座位，也没有扣减车厢摘要，只回滚 reservationId 对应的统一区间库存。
        List<RouteDTO> affectedRoutes = trainStationService.listTakeoutTrainStationRoute(
                trainId, departure, arrival);
        ticketInventoryService.rollbackInUnified(
                trainId, serviceDate, affectedRoutes, seatType, seatCount, rollbackKey);
    }

    /**
     * 加载并缓存统一库存车厢摘要回滚脚本。
     *
     * @return Redis 回滚脚本
     */
    private DefaultRedisScript<Long> getRollbackScript() {
        return Singleton.get(LUA_CARRIAGE_SUMMARY_ROLLBACK_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(
                    new ClassPathResource(LUA_CARRIAGE_SUMMARY_ROLLBACK_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
    }
}
