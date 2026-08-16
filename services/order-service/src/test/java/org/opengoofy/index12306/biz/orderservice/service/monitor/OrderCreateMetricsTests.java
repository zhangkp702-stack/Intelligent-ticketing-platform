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

package org.opengoofy.index12306.biz.orderservice.service.monitor;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证订单创建阶段指标的固定标签和内存计数行为。
 */
class OrderCreateMetricsTests {

    /**
     * 验证相同阶段和结果复用同一个计时器并累计调用次数。
     */
    @Test
    void sameStageShouldReuseTimerAndAccumulateCount() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OrderCreateMetrics metrics = new OrderCreateMetrics(meterRegistry);

        // 连续记录同一固定阶段，注册表中只应存在一组低基数标签。
        Timer.Sample firstSample = metrics.startStageTimer();
        metrics.recordStage(firstSample, "command_prepare", "success");
        Timer.Sample secondSample = metrics.startStageTimer();
        metrics.recordStage(secondSample, "command_prepare", "success");

        Timer timer = meterRegistry.get("index12306.order.create.stage.duration")
                .tag("stage", "command_prepare")
                .tag("result", "success")
                .timer();
        assertThat(timer.count()).isEqualTo(2L);
        assertThat(meterRegistry.find("index12306.order.create.stage.duration")
                .tag("stage", "command_prepare")
                .tag("result", "success")
                .timers()).hasSize(1);
    }
}
