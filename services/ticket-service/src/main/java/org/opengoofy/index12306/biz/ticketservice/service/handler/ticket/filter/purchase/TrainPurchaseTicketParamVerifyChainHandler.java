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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.purchase;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.common.toolkit.EnvironmentUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_STOPOVER_DETAIL;

/**
 * 购票流程过滤器之验证参数是否有效
 * 验证参数有效这个流程会大量交互缓存，为了优化性能需要使用 Lua。为了方便大家理解流程，这里使用多次调用缓存
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Component
@RequiredArgsConstructor
public class TrainPurchaseTicketParamVerifyChainHandler implements TrainPurchaseTicketChainFilter<PurchaseTicketReqDTO> {

    private final TrainMapper trainMapper;
    private final TrainStationMapper trainStationMapper;
    private final DistributedCache distributedCache;
    private final TicketPurchaseMetrics ticketPurchaseMetrics;

    /**
     * 分别校验车次状态和站点顺序，并独立记录两个缓存读取阶段的耗时。
     *
     * @param requestParam 已完成必填校验的购票请求
     */
    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        Timer.Sample trainTimer = ticketPurchaseMetrics.startStageTimer();
        String trainResult = "failed";
        try {
            // 查询车次基础信息，缓存未命中时才回源数据库。
            TrainDO trainDO = distributedCache.safeGet(
                    TRAIN_INFO + requestParam.getTrainId(),
                    TrainDO.class,
                    () -> trainMapper.selectById(requestParam.getTrainId()),
                    ADVANCE_TICKET_DAY,
                    TimeUnit.DAYS);
            if (Objects.isNull(trainDO)) {
                throw new ClientException("请检查车次是否存在");
            }
            // 非开发环境继续校验发售和发车时间，阻止过期车次消耗库存资源。
            if (!EnvironmentUtil.isDevEnvironment()) {
                if (new Date().before(trainDO.getSaleTime())) {
                    throw new ClientException("列车车次暂未发售");
                }
                if (new Date().after(trainDO.getDepartureTime())) {
                    throw new ClientException("列车车次已出发禁止购票");
                }
            }
            trainResult = "success";
        } finally {
            ticketPurchaseMetrics.recordStage(trainTimer, "train_verify", trainResult);
        }

        Timer.Sample stationTimer = ticketPurchaseMetrics.startStageTimer();
        String stationResult = "failed";
        try {
            // 查询完整经停站列表并校验用户区间方向，缓存未命中时才回源数据库。
            String trainStationStopoverDetailStr = distributedCache.safeGet(
                    TRAIN_STATION_STOPOVER_DETAIL + requestParam.getTrainId(),
                    String.class,
                    () -> {
                        LambdaQueryWrapper<TrainStationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationDO.class)
                                .eq(TrainStationDO::getTrainId, requestParam.getTrainId())
                                .select(TrainStationDO::getDeparture);
                        List<TrainStationDO> actualTrainStationList = trainStationMapper.selectList(queryWrapper);
                        return CollUtil.isNotEmpty(actualTrainStationList) ? JSON.toJSONString(actualTrainStationList) : null;
                    },
                    Index12306Constant.ADVANCE_TICKET_DAY,
                    TimeUnit.DAYS
            );
            List<TrainStationDO> trainDOList = JSON.parseArray(trainStationStopoverDetailStr, TrainStationDO.class);
            boolean validateStation = validateStation(
                    trainDOList.stream().map(TrainStationDO::getDeparture).toList(),
                    requestParam.getDeparture(),
                    requestParam.getArrival()
            );
            if (!validateStation) {
                throw new ClientException("列车车站数据错误");
            }
            stationResult = "success";
        } finally {
            ticketPurchaseMetrics.recordStage(stationTimer, "station_verify", stationResult);
        }
    }

    @Override
    public int getOrder() {
        return 10;
    }

    public boolean validateStation(List<String> stationList, String startStation, String endStation) {
        int index1 = stationList.indexOf(startStation);
        int index2 = stationList.indexOf(endStation);
        if (index1 == -1 || index2 == -1) {
            return false;
        }
        return index2 >= index1;
    }
}
