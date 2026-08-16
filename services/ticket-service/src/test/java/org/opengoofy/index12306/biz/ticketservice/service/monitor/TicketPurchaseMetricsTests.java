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

package org.opengoofy.index12306.biz.ticketservice.service.monitor;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证购票阶段指标的预注册和复用行为。
 */
class TicketPurchaseMetricsTests {

    /**
     * 验证固定阶段在首个业务样本之前已经注册，记录时复用同一个计时器。
     */
    @Test
    void fixedStageShouldBeRegisteredBeforeFirstSampleAndReused() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TicketPurchaseMetrics metrics = new TicketPurchaseMetrics(meterRegistry);

        // 构造完成后即可读取固定阶段，说明首个业务请求不会再创建该 Meter。
        Timer timer = meterRegistry.get("index12306.ticket.purchase.stage.duration")
                .tag("stage", "inventory_ready")
                .tag("result", "success")
                .timer();
        assertThat(timer.count()).isZero();

        // 记录一次样本后应在同一 Timer 上累计，不能生成重复的标签组合。
        Timer.Sample sample = metrics.startStageTimer();
        metrics.recordStage(sample, "inventory_ready", "success");
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(meterRegistry.find("index12306.ticket.purchase.stage.duration")
                .tag("stage", "inventory_ready")
                .tag("result", "success")
                .timers()).hasSize(1);
    }
}
