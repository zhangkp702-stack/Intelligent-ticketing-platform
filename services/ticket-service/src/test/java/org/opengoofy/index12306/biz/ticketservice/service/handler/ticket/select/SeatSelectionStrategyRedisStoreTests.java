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
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEFAULTS;

/**
 * 验证 Redis 共享状态机脚本的 Lua 返回状态转换。
 */
class SeatSelectionStrategyRedisStoreTests {

    /**
     * 状态迁移脚本返回的字段必须转换为完整领域状态，供主链路确定性路由。
     */
    @Test
    void convertsAtomicStateTransitionResult() {
        DistributedCache distributedCache = mock(DistributedCache.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, invocation -> {
            // Spring Redis 的 execute 使用可变参数；按方法名返回脚本结果避免 Mockito 展开参数的匹配差异。
            if ("execute".equals(invocation.getMethod().getName())) {
                return List.of("SINGLE", 2L, 1_000L, 1_500L, 0L, 0L, "normal_conflict_high");
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(distributedCache.getInstance()).thenReturn(redisTemplate);
        SeatSelectionStrategyRedisStore store = new SeatSelectionStrategyRedisStore(distributedCache);
        SeatSelectionStrategyStateConfig config = new SeatSelectionStrategyStateConfig(
                500L, 20, 7_000, 5_000, 40, 5_000L, 5, 3);

        SeatSelectionStrategyState state = store.transition("1001_20240813_A_B_1",
                new SeatConflictStatistics(20L, 15L, 20L),
                new SeatConflictStatistics(0L, 0L, 0L), 40, config, 2_000L);

        assertEquals(SeatSelectionStrategyMode.SINGLE, state.mode());
        assertEquals(2L, state.version());
        assertEquals(0, state.optimisticPercentage());
        assertEquals("normal_conflict_high", state.reason());
    }
}
