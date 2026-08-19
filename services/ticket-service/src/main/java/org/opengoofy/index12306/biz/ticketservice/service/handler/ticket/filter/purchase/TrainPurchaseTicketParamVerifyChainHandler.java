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

import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PurchaseExecutionContext;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.opengoofy.index12306.framework.starter.common.toolkit.EnvironmentUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 购票流程过滤器之验证参数是否有效
 * 车次和站点静态数据来自单次请求上下文，本处理器只执行本地业务校验。
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Component
@RequiredArgsConstructor
public class TrainPurchaseTicketParamVerifyChainHandler implements TrainPurchaseTicketChainFilter {

    private final TicketPurchaseMetrics ticketPurchaseMetrics;

    /**
     * 分别校验车次状态和站点顺序，并独立记录两个缓存读取阶段的耗时。
     *
     * @param context 已从启动快照加载车次和有序站点的购票执行上下文
     */
    @Override
    public void handler(PurchaseExecutionContext context) {
        // 同一次请求的校验、令牌桶和锁座阶段共享车次及站点快照，不再重复访问 Redis。
        PurchaseTicketReqDTO requestParam = context.requestParam();
        Timer.Sample trainTimer = ticketPurchaseMetrics.startStageTimer();
        String trainResult = "failed";
        try {
            // 车次已经在启动阶段预热，本阶段只执行状态和时间窗口校验。
            TrainDO trainDO = context.train();
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
            // 直接使用有序站名快照校验方向，消除 Redis 往返和 JSON 反序列化。
            boolean validateStation = validateStation(
                    context.stationNames(),
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

    /**
     * 校验出发站和到达站均存在且方向严格向后。
     *
     * @param stationList 当前车次的有序站点
     * @param startStation 出发站
     * @param endStation 到达站
     * @return 区间存在且方向有效时返回 true
     */
    public boolean validateStation(List<String> stationList, String startStation, String endStation) {
        // 两个站点必须存在，并且到达站不能与出发站相同或位于其前方。
        int index1 = stationList.indexOf(startStation);
        int index2 = stationList.indexOf(endStation);
        if (index1 == -1 || index2 == -1) {
            return false;
        }
        return index2 > index1;
    }
}
