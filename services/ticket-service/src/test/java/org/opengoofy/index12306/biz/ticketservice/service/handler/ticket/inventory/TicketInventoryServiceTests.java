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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证统一库存的键结构和只读查询契约。
 */
class TicketInventoryServiceTests {

    /**
     * 统一库存键必须同时包含车次和始发日期，区间席别只作为 Hash Field。
     */
    @Test
    void buildsDateIsolatedInventoryKeyAndIntervalField() {
        Date serviceDate = Date.from(LocalDate.of(2026, 8, 20)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());

        // 车次和始发日期共同决定库存槽位，操作标记使用相同的日期隔离规则。
        assertEquals("index12306-ticket-service:train_inventory:{1001:20260820}",
                TicketInventoryKeyUtil.buildInventoryKey("1001", serviceDate));
        assertEquals("index12306-ticket-service:train_inventory_operation:{1001:20260820}",
                TicketInventoryKeyUtil.buildOperationMarkerKey("1001", serviceDate));
        assertEquals("北京南_济南西_1",
                TicketInventoryKeyUtil.buildInventoryField("北京南", "济南西", 1));
    }

    /**
     * 缺少始发日期时必须拒绝构造库存键，避免不同开行日共用库存。
     */
    @Test
    void rejectsInventoryKeyWithoutServiceDate() {
        assertThrows(NullPointerException.class,
                () -> TicketInventoryKeyUtil.buildInventoryKey("1001", null));
    }

    /**
     * 统一库存查询只读取对应 Hash 字段，不触发旧库存或数据库回源。
     */
    @Test
    @SuppressWarnings("unchecked")
    void queriesUnifiedInventoryHash() {
        DistributedCache distributedCache = mock(DistributedCache.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(distributedCache.getInstance()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Date serviceDate = Date.from(LocalDate.of(2026, 8, 20)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        String inventoryKey = "index12306-ticket-service:train_inventory:{1001:20260820}";
        when(hashOperations.get(inventoryKey, "北京南_济南西_1")).thenReturn("36");
        TicketInventoryService service = new TicketInventoryService(distributedCache, new SimpleMeterRegistry());

        // 统一库存服务直接返回 Hash 中的数量，调用方可据此进行购票预校验。
        assertEquals(36, service.query("1001", serviceDate, "北京南", "济南西", 1).orElseThrow());
        verify(hashOperations).get(inventoryKey, "北京南_济南西_1");
    }
}
