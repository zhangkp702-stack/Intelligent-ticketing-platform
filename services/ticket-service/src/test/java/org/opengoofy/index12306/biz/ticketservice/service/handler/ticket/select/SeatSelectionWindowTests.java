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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证阶段四 Redis 窗口与阶段三策略规划保持一致。
 */
class SeatSelectionWindowTests {

    /**
     * 验证普通快速、普通稳定和探测窗口具有约定的时长与样本隔离。
     */
    @Test
    void alignsRedisWindowsWithSelectionStrategyPlan() {
        // 所有窗口共用百毫秒桶，快速、稳定和探测窗口分别覆盖五百毫秒、两秒和一秒。
        assertEquals(100L, SeatSelectionWindow.BUCKET_MILLIS);
        assertEquals(500L, duration(SeatSelectionWindow.NORMAL_FAST));
        assertEquals(2_000L, duration(SeatSelectionWindow.NORMAL_STABLE));
        assertEquals(1_000L, duration(SeatSelectionWindow.PROBE));

        // 探测流量必须与普通乐观流量分开统计，防止恢复样本影响降级判断。
        assertEquals(SeatSelectionSampleType.NORMAL, SeatSelectionWindow.NORMAL_FAST.sampleType());
        assertEquals(SeatSelectionSampleType.NORMAL, SeatSelectionWindow.NORMAL_STABLE.sampleType());
        assertEquals(SeatSelectionSampleType.PROBE, SeatSelectionWindow.PROBE.sampleType());
    }

    /**
     * 计算固定窗口覆盖时长。
     *
     * @param window 待计算的 Redis 统计窗口
     * @return 窗口覆盖毫秒数
     */
    private long duration(SeatSelectionWindow window) {
        // 窗口时长等于统一桶宽乘以该窗口固定桶数。
        return SeatSelectionWindow.BUCKET_MILLIS * window.bucketCount();
    }
}
