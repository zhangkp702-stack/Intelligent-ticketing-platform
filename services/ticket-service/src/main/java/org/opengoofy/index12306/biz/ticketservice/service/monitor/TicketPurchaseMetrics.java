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
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 记录购票链路的低基数指标，帮助定位请求在令牌、选座、落库或订单创建阶段的耗时和失败。
 *
 * <p>指标标签只允许使用固定阶段和固定结果，订单号、reservationId、车次、站点及日期通过日志关联，避免监控系统产生高基数时序。</p>
 */
@Component
public class TicketPurchaseMetrics {

    private static final String STAGE_DURATION_METRIC = "index12306.ticket.purchase.stage.duration";
    private static final String OUTCOME_METRIC = "index12306.ticket.purchase.outcome";
    private static final String FAILURE_METRIC = "index12306.ticket.purchase.failure";
    private static final String SELECTION_STRATEGY_METRIC = "index12306.ticket.purchase.selection.strategy";
    private static final String TOTAL_STAGE = "purchase_total";
    private static final String[][] PRE_REGISTERED_STAGE_RESULTS = {
            {"request_validation", "success"},
            {"purchase_date_validate", "success"}, {"purchase_date_validate", "failed"},
            {"service_date_resolve", "success"}, {"service_date_resolve", "failed"},
            {"param_not_null", "success"}, {"param_not_null", "failed"},
            {"train_verify", "success"}, {"train_verify", "failed"},
            {"station_verify", "success"}, {"station_verify", "failed"},
            {"stock_verify", "success"}, {"stock_verify", "failed"},
            {"repeat_verify", "success"}, {"repeat_verify", "failed"},
            {"inventory_token", "success"}, {"inventory_token", "rejected"},
            {"inventory_ready", "success"}, {"inventory_ready", "failed"},
            {"candidate_carriage", "success"}, {"candidate_carriage", "failed"},
            {"strategy_decide", "success"}, {"strategy_decide", "failed"},
            {"seat_allocate", "success"}, {"seat_allocate", "failed"},
            {"passenger_remote", "success"}, {"passenger_remote", "failed"},
            {"price_query", "success"}, {"price_query", "failed"},
            {"seat_selection", "success"}, {"seat_selection", "failed"},
            {"redis_hold", "success"}, {"redis_hold", "conflict"}, {"redis_hold", "failed"},
            {"database_confirm", "success"}, {"database_confirm", "conflict"}, {"database_confirm", "failed"},
            {"ticket_persist", "success"}, {"ticket_persist", "failed"},
            {"reservation_prepare", "success"}, {"reservation_prepare", "failed"},
            {"order_create", "success"}, {"order_create", "failed"},
            {"reservation_bind", "success"}, {"reservation_bind", "failed"},
            {"redis_compensation", "success"}, {"redis_compensation", "failed"},
            {TOTAL_STAGE, "success"}, {TOTAL_STAGE, "no_ticket"}, {TOTAL_STAGE, "failed"}
    };

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<StageKey, Timer> stageTimers = new ConcurrentHashMap<>();

    /**
     * 创建购票指标组件，并在业务流量到达前注册所有固定阶段计时器。
     *
     * @param meterRegistry 应用指标注册表
     */
    public TicketPurchaseMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // 固定标签组合在 Bean 初始化阶段完成注册，首个购票请求不再承担直方图和 Meter 创建成本。
        for (String[] stageResult : PRE_REGISTERED_STAGE_RESULTS) {
            StageKey stageKey = new StageKey(stageResult[0], stageResult[1]);
            stageTimers.put(stageKey, registerStageTimer(stageKey));
        }
    }

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
        // 调用方只传固定阶段和结果；预注册集合之外的异常组合仍只创建一次并被缓存复用。
        StageKey stageKey = new StageKey(stage, result);
        Timer stageTimer = stageTimers.computeIfAbsent(stageKey, this::registerStageTimer);
        sample.stop(stageTimer);
    }

    /**
     * 注册固定标签的阶段计时器，仅购票总耗时维护百分位直方图。
     *
     * @param stageKey 阶段和结果组成的低基数键
     * @return 已注册并可复用的阶段计时器
     */
    private Timer registerStageTimer(StageKey stageKey) {
        // 子阶段只累计次数和总耗时，避免每个细分阶段维护直方图放大高并发指标开销。
        Timer.Builder timerBuilder = Timer.builder(STAGE_DURATION_METRIC)
                .description("Ticket purchase stage duration")
                .tags("stage", stageKey.stage(), "result", stageKey.result());
        if (TOTAL_STAGE.equals(stageKey.stage())) {
            timerBuilder.publishPercentileHistogram();
        }
        return timerBuilder.register(meterRegistry);
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

    /**
     * 阶段名称和结果均由服务端固定代码提供，禁止放入车次、订单号或用户标识。
     *
     * @param stage 固定阶段名称
     * @param result 固定执行结果
     */
    private record StageKey(String stage, String result) {
    }
}
