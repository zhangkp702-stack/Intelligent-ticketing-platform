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
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 reservation 关闭时只使用订单级席别和人数回滚统一库存。
 */
class TicketInventoryRollbackServiceTests {

    /**
     * 验证统一库存与车厢摘要从 reservation 的单席别字段和座位快照对称恢复。
     */
    @Test
    @SuppressWarnings("unchecked")
    void rollsBackReservationWithSingleSeatTypeAndSeatCount() {
        TrainStationService trainStationService = mock(TrainStationService.class);
        DistributedCache distributedCache = mock(DistributedCache.class);
        TicketInventoryService ticketInventoryService = mock(TicketInventoryService.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        Date serviceDate = new Date(1785283200000L);
        List<RouteDTO> affectedRoutes = List.of(new RouteDTO("A", "B"));
        List<TrainPurchaseTicketRespDTO> tickets = List.of(ticket("01", "01A"), ticket("02", "02A"));
        when(trainStationService.listTakeoutTrainStationRoute("1001", "A", "B")).thenReturn(affectedRoutes);
        when(distributedCache.getInstance()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(0L);
        TicketInventoryRollbackService service = new TicketInventoryRollbackService(
                trainStationService, distributedCache, ticketInventoryService);

        // reservation 的 seatType 和 seatCount 是库存回补真值，座位快照只提供车厢摘要维度。
        boolean rolledBack = service.rollbackIfNecessary(
                "1001", serviceDate, "A", "B", 1, 2, tickets, "reservation-a");

        ArgumentCaptor<Object> summaryJsonCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ticketInventoryService).rollbackInUnified(
                eq("1001"), eq(serviceDate), eq(affectedRoutes), eq(1), eq(2L), eq("reservation-a"));
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), summaryJsonCaptor.capture());
        JSONArray summaries = JSON.parseArray(summaryJsonCaptor.getValue().toString());
        assertTrue(rolledBack);
        assertEquals(2, summaries.size());
        assertTrue(summaries.stream().map(JSONObject.class::cast)
                .allMatch(each -> each.getString("summaryKey").endsWith("_1")));
    }

    /**
     * 构造仅含 Redis 位图和车厢摘要所需字段的座位快照。
     *
     * @param carriageNumber 车厢号
     * @param seatNumber 座位号
     * @return 固定席别的座位快照
     */
    private TrainPurchaseTicketRespDTO ticket(String carriageNumber, String seatNumber) {
        // seatType 留在快照中服务于数据库解锁，但库存回补不从该字段推导订单席别。
        TrainPurchaseTicketRespDTO ticket = new TrainPurchaseTicketRespDTO();
        ticket.setSeatType(1);
        ticket.setCarriageNumber(carriageNumber);
        ticket.setSeatNumber(seatNumber);
        return ticket;
    }
}
