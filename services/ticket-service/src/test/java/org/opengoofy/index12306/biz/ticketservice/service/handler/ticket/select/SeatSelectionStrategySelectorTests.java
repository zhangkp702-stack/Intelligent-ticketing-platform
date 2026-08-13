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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageAvailabilityDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证座位策略选择器的低余票和高冲突降级规则。
 */
class SeatSelectionStrategySelectorTests {

    /**
     * 低余票应立即进入车厢区间锁单通道，不等待冲突窗口累积。
     */
    @Test
    void usesSingleChannelWhenCandidateStockIsLow() {
        SeatSelectionStrategySelector selector = new SeatSelectionStrategySelector();

        assertTrue(selector.shouldUseSingleChannel(request(), 1, List.of(new CarriageAvailabilityDTO("01", 40))));
    }

    /**
     * 高余票初始走乐观通道，达到足量高冲突样本后才降级为单通道。
     */
    @Test
    void usesSingleChannelWhenOptimisticConflictRateIsHigh() {
        SeatSelectionStrategySelector selector = new SeatSelectionStrategySelector();
        PurchaseTicketReqDTO request = request();
        List<CarriageAvailabilityDTO> highStock = List.of(new CarriageAvailabilityDTO("01", 100));

        assertFalse(selector.shouldUseSingleChannel(request, 1, highStock));
        for (int index = 0; index < 20; index++) {
            // 模拟连续的 Redis 乐观占位资源冲突。
            selector.recordOptimisticSelectionResult(request, 1, true);
        }

        assertTrue(selector.shouldUseSingleChannel(request, 1, highStock));
    }

    /**
     * 构造固定始发日期的购票请求，保证同一运行库存命中同一冲突窗口。
     *
     * @return 用于策略选择器测试的购票请求
     */
    private PurchaseTicketReqDTO request() {
        // 策略键只依赖车次、始发日期、区间与座位类型，测试设置这些稳定字段即可。
        PurchaseTicketReqDTO request = new PurchaseTicketReqDTO();
        request.setTrainId("1001");
        request.setServiceDate(Date.from(LocalDate.of(2026, 8, 13).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()));
        request.setDeparture("南京南");
        request.setArrival("上海虹桥");
        return request;
    }
}
