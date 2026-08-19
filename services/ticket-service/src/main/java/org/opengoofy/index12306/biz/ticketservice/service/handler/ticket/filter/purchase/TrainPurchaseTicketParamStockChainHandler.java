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

import cn.hutool.core.util.StrUtil;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PurchaseExecutionContext;
import org.opengoofy.index12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 购票流程过滤器之验证列车站点库存是否充足
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Component
@RequiredArgsConstructor
public class TrainPurchaseTicketParamStockChainHandler implements TrainPurchaseTicketChainFilter {

    // 座位余票缓存加载器，当缓存中没有座位余票信息时，调用该加载器用于从缓存中加载座位余票信息
    private final SeatMarginCacheLoader seatMarginCacheLoader;
    private final DistributedCache distributedCache;
    private final TicketPurchaseMetrics ticketPurchaseMetrics;

    /**
     * 按席别校验区间余票摘要，并分别记录 Redis 命中或缓存加载所消耗的时间。
     *
     * @param context 已完成静态数据准备的购票执行上下文
     */
    @Override
    public void handler(PurchaseExecutionContext context) {
        Timer.Sample stockTimer = ticketPurchaseMetrics.startStageTimer();
        String stockResult = "failed";
        try {
            // 库存键仍由请求参数组成，车次静态数据不再在本处理器重复读取。
            PurchaseTicketReqDTO requestParam = context.requestParam();
            // 车次站点是否还有余票。如果用户提交多个乘车人非同一座位类型，拆分验证
            // 凭借车次 ID、出发站、到达站作为缓存键，获取车次站点的余票信息
            String keySuffix = StrUtil.join("_", requestParam.getTrainId(), ServiceDateKeyUtil.format(requestParam.getServiceDate()),
                    requestParam.getDeparture(), requestParam.getArrival());
            // 从缓存组件中获取StringRedisTemplate实例
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            // 获取所有乘车人
            List<PurchaseTicketPassengerDetailDTO> passengerDetails = requestParam.getPassengers();
            // 按照座位类型分组变成一个map，key是座位类型，value是该座位类型的乘车人列表
            Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = passengerDetails.stream()
                    .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));

            seatTypeMap.forEach((seatType, passengerSeatDetails) -> {
                // 利用redis的hash结构缓存作为信息，key是座位类型，field是座位类型对应的余票数量
                Object stockObj = stringRedisTemplate.opsForHash().get(TRAIN_STATION_REMAINING_TICKET + keySuffix, String.valueOf(seatType));
                int stock = Optional.ofNullable(stockObj)
                        // 如果stockObj不为null就转换为整数
                        .map(each -> Integer.parseInt(each.toString()))
                        // 如果没查到就调用缓存加载器获取车子起点还有终点的票
                        .orElseGet(() -> {
                            Map<String, String> seatMarginMap = seatMarginCacheLoader.load(
                                    String.valueOf(requestParam.getTrainId()),
                                    requestParam.getServiceDate(),
                                    String.valueOf(seatType), requestParam.getDeparture(),
                                    requestParam.getArrival());
                            // 返回获取到的座位位置
                            return Optional.ofNullable(seatMarginMap.get(String.valueOf(seatType)))
                                    .map(Integer::parseInt)
                                    .orElse(0);
                        });
                // 比较余票数量和乘车人数，粗粒度不足时不再进入真实锁座阶段。
                if (stock >= passengerSeatDetails.size()) {
                    return;
                }
                throw new ClientException("列车站点已无余票");
            });
            stockResult = "success";
        } finally {
            // 缓存加载或业务校验异常均归入失败标签，方便识别该过滤器是否成为瓶颈。
            ticketPurchaseMetrics.recordStage(stockTimer, "stock_verify", stockResult);
        }
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
