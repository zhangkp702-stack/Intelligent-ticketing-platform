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

package org.opengoofy.index12306.biz.ticketservice.canal;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.CanalExecuteStrategyMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.mq.event.CanalBinlogEvent;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractExecuteStrategy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TICKET_AVAILABILITY_TOKEN_BUCKET;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 列车余票缓存更新组件。
 * 位图改造后，不再基于旧 seat_status 做增量加减，
 * 而是直接删除受影响车次的余票缓存与令牌桶，由后续请求按 DB 位图回源重建。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketAvailabilityCacheUpdateHandler implements AbstractExecuteStrategy<CanalBinlogEvent, Void> {

    private final TrainMapper trainMapper;
    private final TrainStationService trainStationService;
    private final DistributedCache distributedCache;

    @Override
    public void execute(CanalBinlogEvent message) {
        Set<String> trainIds = extractTrainIds(message);
        if (trainIds.isEmpty()) {
            return;
        }
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        trainIds.forEach(trainId -> {
            TrainDO trainDO = trainMapper.selectById(trainId);
            if (trainDO == null) {
                return;
            }
            List<RouteDTO> routeDTOList = trainStationService.listTrainStationRoute(trainId, trainDO.getStartStation(), trainDO.getEndStation());
            routeDTOList.forEach(each -> stringRedisTemplate.delete(
                    TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", trainId, each.getStartStation(), each.getEndStation())));
            stringRedisTemplate.delete(TICKET_AVAILABILITY_TOKEN_BUCKET + trainId);
            log.info("[位图模式] 已删除车次余票缓存和令牌桶 trainId={}", trainId);
        });
    }

    private Set<String> extractTrainIds(CanalBinlogEvent message) {
        Set<String> result = new LinkedHashSet<>();
        List<Map<String, Object>> data = message.getData();
        if (data == null) {
            return result;
        }
        data.forEach(each -> {
            Object trainId = each.get("train_id");
            if (trainId != null) {
                result.add(String.valueOf(trainId));
            }
        });
        return result;
    }

    @Override
    public String mark() {
        return CanalExecuteStrategyMarkEnum.T_SEAT.getActualTable();
    }
}
