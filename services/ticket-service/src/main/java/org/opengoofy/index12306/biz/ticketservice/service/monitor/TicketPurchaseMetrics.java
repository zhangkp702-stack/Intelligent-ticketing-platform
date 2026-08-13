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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 记录购票链路的低基数指标，帮助定位请求在令牌、选座、落库或订单创建阶段的耗时和失败。
 *
 * <p>指标标签只允许使用固定阶段和固定结果，订单号、reservationId、车次、站点及日期通过日志关联，避免监控系统产生高基数时序。</p>
 */
@Component
@RequiredArgsConstructor
public class TicketPurchaseMetrics {

    private static final String STAGE_DURATION_METRIC = "index12306.ticket.purchase.stage.duration";
    private static final String OUTCOME_METRIC = "index12306.ticket.purchase.outcome";
    private static final String FAILURE_METRIC = "index12306.ticket.purchase.failure";
    private static final String SELECTION_STRATEGY_METRIC = "index12306.ticket.purchase.selection.strategy";

    private final MeterRegistry meterRegistry;

    /**
     * 创建一个用于统计当前购票阶段耗时的计时样本。
     *
     * @return 已绑定当前 MeterRegistry 的计时样本
     */
    public Timer.Sample startStageTimer() {
        // 计时样本只在当前请求内存活，结束时由固定阶段名归档。
        return Timer.start(meterRegistry);
    }

    /**
     * 记录购票阶段耗时。
     *
     * @param sample 阶段开始时创建的计时样本
     * @param stage 固定的链路阶段名称
     * @param result 固定的阶段执行结果
     */
    public void recordStage(Timer.Sample sample, String stage, String result) {
        // 仅将固定枚举值作为标签，避免压测账号或订单标识污染 Prometheus 时序。
        sample.stop(Timer.builder(STAGE_DURATION_METRIC)
                .description("Ticket purchase stage duration")
                .tags("stage", stage, "result", result)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    /**
     * 记录一次购票请求的最终结果。
     *
     * @param outcome 固定的购票结果分类
     */
    public void recordOutcome(String outcome) {
        // 成功、无票和系统失败分别计数，不能只根据 HTTP 状态码判断购票结果。
        Counter.builder(OUTCOME_METRIC)
                .description("Ticket purchase outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录一次购票阶段失败。
     *
     * @param stage 固定的失败发生阶段
     * @param reason 固定的失败原因分类
     */
    public void recordFailure(String stage, String reason) {
        // 阶段与原因均由服务端枚举，便于聚合且不会产生用户维度时序。
        Counter.builder(FAILURE_METRIC)
                .description("Ticket purchase failures")
                .tags("stage", stage, "reason", reason)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录本次请求最终使用的座位分配通道。
     *
     * @param strategy 固定的座位分配策略名称
     */
    public void recordSelectionStrategy(String strategy) {
        // 策略标签只允许 optimistic 或 single_channel，便于压测时比较切换效果。
        Counter.builder(SELECTION_STRATEGY_METRIC)
                .description("Ticket seat selection strategy")
                .tag("strategy", strategy)
                .register(meterRegistry)
                .increment();
    }
}
