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
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.PurchaseExecutionContext;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.inventory.TicketInventoryService;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 购票流程过滤器之验证列车站点库存是否充足
 */
@Component
@RequiredArgsConstructor
public class TrainPurchaseTicketParamStockChainHandler implements TrainPurchaseTicketChainFilter {

    private final TicketInventoryService ticketInventoryService;
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
            PurchaseTicketReqDTO requestParam = context.requestParam();
            List<PurchaseTicketPassengerDetailDTO> passengerDetails = requestParam.getPassengers();
            Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = passengerDetails.stream()
                    .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));

            seatTypeMap.forEach((seatType, passengerSeatDetails) -> {
                // 预校验读取与扣减同一个统一库存字段，缺失表示迁移故障而非可回源的缓存未命中。
                int stock = ticketInventoryService.query(
                                requestParam.getTrainId(), requestParam.getServiceDate(), requestParam.getDeparture(),
                                requestParam.getArrival(), seatType)
                        .orElseThrow(() -> new ServiceException("统一库存未完成初始化，请稍候再试"));
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
