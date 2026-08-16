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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 记录订单创建链路的低基数阶段耗时，避免通过逐请求日志观察性能。
 */
@Component
public class OrderCreateMetrics {

    private static final String STAGE_DURATION_METRIC = "index12306.order.create.stage.duration";
    private static final String TOTAL_STAGE = "order_create_total";
    private static final String[][] PRE_REGISTERED_STAGE_RESULTS = {
            {TOTAL_STAGE, "success"}, {TOTAL_STAGE, "failed"},
            {"command_prepare", "success"}, {"command_prepare", "failed"},
            {"order_transaction", "success"}, {"order_transaction", "failed"},
            {"order_insert", "success"}, {"order_insert", "duplicate"}, {"order_insert", "failed"},
            {"order_item_persist", "success"}, {"order_item_persist", "failed"},
            {"command_complete", "success"}, {"command_complete", "failed"},
            {"delay_close_enqueue", "success"}, {"delay_close_enqueue", "failed"}
    };

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<StageKey, Timer> stageTimers = new ConcurrentHashMap<>();

    /**
     * 创建订单指标组件，并在业务流量到达前注册所有固定阶段计时器。
     *
     * @param meterRegistry 应用指标注册表
     */
    public OrderCreateMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // 固定标签组合在 Bean 初始化阶段完成注册，首个创建订单请求不再承担 Meter 创建成本。
        for (String[] stageResult : PRE_REGISTERED_STAGE_RESULTS) {
            StageKey stageKey = new StageKey(stageResult[0], stageResult[1]);
            stageTimers.put(stageKey, registerStageTimer(stageKey));
        }
    }

    /**
     * 创建当前阶段的轻量计时样本。
     *
     * @return 绑定当前指标时钟的计时样本
     */
    public Timer.Sample startStageTimer() {
        // 样本只保存单调时钟起点，不执行日志或持久化写入。
        return Timer.start(meterRegistry);
    }

    /**
     * 将阶段耗时记录到预注册的内存指标中。
     *
     * @param sample 阶段开始时创建的计时样本
     * @param stage 固定的阶段名称
     * @param result 固定的执行结果
     */
    public void recordStage(Timer.Sample sample, String stage, String result) {
        // 固定阶段与结果组合只创建一次 Timer，避免每个请求重复构建指标对象。
        StageKey stageKey = new StageKey(stage, result);
        Timer stageTimer = stageTimers.computeIfAbsent(stageKey, this::registerStageTimer);
        sample.stop(stageTimer);
    }

    /**
     * 注册一个固定标签的阶段计时器，仅总耗时生成百分位直方图。
     *
     * @param stageKey 阶段和结果组成的低基数键
     * @return 已注册并可复用的阶段计时器
     */
    private Timer registerStageTimer(StageKey stageKey) {
        // 子阶段只累计次数和耗时，降低高并发下维护多份直方图的成本。
        Timer.Builder timerBuilder = Timer.builder(STAGE_DURATION_METRIC)
                .description("Order create stage duration")
                .tags("stage", stageKey.stage(), "result", stageKey.result());
        if (TOTAL_STAGE.equals(stageKey.stage())) {
            timerBuilder.publishPercentileHistogram();
        }
        return timerBuilder.register(meterRegistry);
    }

    /**
     * 阶段名称和结果均由服务端固定代码提供，禁止放入订单号或用户标识。
     *
     * @param stage 固定阶段名称
     * @param result 固定执行结果
     */
    private record StageKey(String stage, String result) {
    }
}
