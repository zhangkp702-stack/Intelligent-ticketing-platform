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

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketInventoryModeEnum;
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
 * 验证统一库存第一阶段的模式、键结构和只读访问契约。
 */
class TicketInventoryServiceTests {

    /**
     * 统一库存键必须同时包含车次和始发日期，并使用同一个 Redis Hash Tag。
     */
    @Test
    void buildsDateIsolatedInventoryKeyAndIntervalField() {
        Date serviceDate = Date.from(LocalDate.of(2026, 8, 20)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());

        // 车次和始发日期共同决定库存槽位，区间席别只作为 Hash Field。
        assertEquals("index12306-ticket-service:train_inventory:{1001:20260820}",
                TicketInventoryKeyUtil.buildInventoryKey("1001", serviceDate));
        assertEquals("北京南_济南西_1",
                TicketInventoryKeyUtil.buildInventoryField("北京南", "济南西", 1));
    }

    /**
     * 缺少始发日期时必须拒绝构造新库存键，避免跨日期共用库存。
     */
    @Test
    void rejectsInventoryKeyWithoutServiceDate() {
        assertThrows(NullPointerException.class,
                () -> TicketInventoryKeyUtil.buildInventoryKey("1001", null));
    }

    /**
     * 模式配置必须兼容大小写并拒绝未知值。
     */
    @Test
    void parsesInventoryMigrationMode() {
        assertEquals(TicketInventoryModeEnum.LEGACY, TicketInventoryModeEnum.from(null));
        assertEquals(TicketInventoryModeEnum.SHADOW, TicketInventoryModeEnum.from("shadow"));
        assertEquals(TicketInventoryModeEnum.UNIFIED, TicketInventoryModeEnum.from("UNIFIED"));
        assertThrows(IllegalArgumentException.class, () -> TicketInventoryModeEnum.from("invalid"));
    }

    /**
     * 只读服务必须从统一 Hash 的区间席别字段读取数量，且不触发旧库存写入。
     */
    @Test
    @SuppressWarnings("unchecked")
    void queriesUnifiedInventoryWithoutChangingLegacyPath() {
        DistributedCache distributedCache = mock(DistributedCache.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(distributedCache.getInstance()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Date serviceDate = Date.from(LocalDate.of(2026, 8, 20)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        String inventoryKey = "index12306-ticket-service:train_inventory:{1001:20260820}";
        when(hashOperations.get(inventoryKey, "北京南_济南西_1")).thenReturn("36");
        TicketInventoryService service = new TicketInventoryService(
                distributedCache, new TicketInventoryProperties("legacy"));

        // 第一阶段只验证统一库存可读，业务入口仍由 legacy 模式控制。
        assertEquals(TicketInventoryModeEnum.LEGACY, service.mode());
        assertEquals(36, service.query("1001", serviceDate, "北京南", "济南西", 1).orElseThrow());
        verify(hashOperations).get(inventoryKey, "北京南_济南西_1");
    }
}
