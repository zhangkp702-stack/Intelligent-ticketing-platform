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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记录座位占用恢复的稳定结果、耗时和持久化积压，供 Prometheus 聚合告警。
 *
 * <p>标签仅使用固定的恢复链路、结果和积压状态；reservationId、订单号及命令号只能通过结构化日志关联。</p>
 */
@Component
public class TicketSeatReservationRecoveryMetrics {

    private static final String RECOVERY_ATTEMPT_METRIC = "index12306.ticket.reservation.recovery.attempts";
    private static final String RECOVERY_DURATION_METRIC = "index12306.ticket.reservation.recovery.duration";
    private static final String RECOVERY_BACKLOG_METRIC = "index12306.ticket.reservation.recovery.backlog";
    private static final String BACKLOG_REFRESH_FAILURE_METRIC = "index12306.ticket.reservation.recovery.backlog.refresh.failures";

    private final MeterRegistry meterRegistry;
    private final AtomicLong boundIncomplete = new AtomicLong();
    private final AtomicLong preparedPending = new AtomicLong();
    private final AtomicLong releasing = new AtomicLong();
    private final AtomicLong preparedMissingKey = new AtomicLong();

    /**
     * 创建恢复指标记录器，并注册固定维度的数据库积压 Gauge。
     *
     * @param meterRegistry 应用指标注册表
     */
    public TicketSeatReservationRecoveryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerBacklogGauge(BacklogState.BOUND_INCOMPLETE, boundIncomplete);
        registerBacklogGauge(BacklogState.PREPARED_PENDING, preparedPending);
        registerBacklogGauge(BacklogState.RELEASING, releasing);
        registerBacklogGauge(BacklogState.PREPARED_MISSING_KEY, preparedMissingKey);
    }

    /**
     * 记录一条 reservation 恢复尝试的终态和耗时。
     *
     * @param flow 恢复链路类型
     * @param outcome 本次尝试的稳定结果
     * @param startedNanos 调用远程服务或本地恢复前取得的单调时钟值
     */
    public void recordRecovery(RecoveryFlow flow, RecoveryOutcome outcome, long startedNanos) {
        // 固定枚举标签可按恢复分支计算异常率，不引入业务主键造成时序膨胀。
        Counter.builder(RECOVERY_ATTEMPT_METRIC)
                .description("Ticket seat reservation recovery attempts")
                .tags("flow", flow.name(), "outcome", outcome.name())
                .register(meterRegistry)
                .increment();
        Timer.builder(RECOVERY_DURATION_METRIC)
                .description("Ticket seat reservation recovery duration")
                .tags("flow", flow.name(), "outcome", outcome.name())
                .register(meterRegistry)
                .record(elapsed(startedNanos));
    }

    /**
     * 用一次数据库快照刷新各恢复状态的积压 Gauge。
     *
     * @param boundIncompleteCount 已绑定订单但释放步骤未完成的记录数
     * @param preparedPendingCount 等待命令权威终态的 PREPARED 记录数
     * @param releasingCount 已领取失败释放权但尚未终态的记录数
     * @param preparedMissingKeyCount 缺少命令或用户归属键、只能人工核对的 PREPARED 记录数
     */
    public void refreshBacklog(long boundIncompleteCount, long preparedPendingCount, long releasingCount,
                               long preparedMissingKeyCount) {
        // 仅保存最近一次完整快照，读库失败时由调用方保留旧值并记录采集异常。
        boundIncomplete.set(nonNegative(boundIncompleteCount));
        preparedPending.set(nonNegative(preparedPendingCount));
        releasing.set(nonNegative(releasingCount));
        preparedMissingKey.set(nonNegative(preparedMissingKeyCount));
    }

    /**
     * 记录一次恢复积压快照读取失败。
     */
    public void recordBacklogRefreshFailure() {
        // 采集异常与真实零积压必须拆分，避免旧 Gauge 被误判为当前数据库状态。
        Counter.builder(BACKLOG_REFRESH_FAILURE_METRIC)
                .description("Ticket seat reservation recovery backlog refresh failures")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 注册一个固定状态的恢复积压 Gauge。
     *
     * @param state 持久化积压状态
     * @param value 最近一次读取到的数量
     */
    private void registerBacklogGauge(BacklogState state, AtomicLong value) {
        // 状态标签来自枚举，不能拼入订单、用户或 reservation 标识。
        Gauge.builder(RECOVERY_BACKLOG_METRIC, value, AtomicLong::doubleValue)
                .tag("state", state.name())
                .register(meterRegistry);
    }

    /**
     * 将单调时钟差转换为非负耗时。
     *
     * @param startedNanos 记录开始时间
     * @return 可安全提交到计时器的持续时间
     */
    private Duration elapsed(long startedNanos) {
        // 系统时钟回拨不影响单调时钟；仍防御测试桩传入未来时间的场景。
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos));
    }

    /**
     * 限制 Gauge 值为非负数。
     *
     * @param value 原始数据库计数
     * @return 非负的计数快照
     */
    private long nonNegative(long value) {
        // SQL COUNT 理论上非负，防御错误 mock 或调用方计算错误污染监控。
        return Math.max(0L, value);
    }

    /** 已绑定订单的关闭回滚与未绑定订单的命令对账两条恢复链路。 */
    public enum RecoveryFlow {
        /** 已绑定订单的关闭回滚恢复。 */
        CLOSED_ORDER,
        /** PREPARED 或 RELEASING reservation 的订单命令对账。 */
        PREPARED_COMMAND
    }

    /** 恢复尝试产生的固定低基数结果。 */
    public enum RecoveryOutcome {
        /** 已确认订单关闭并交由可靠回滚继续释放。 */
        ORDER_CLOSED,
        /** 订单尚未关闭，继续保留真实库存。 */
        ORDER_NOT_CLOSED,
        /** 查询订单服务未获得有效权威结果。 */
        ORDER_QUERY_FAILED,
        /** 命令终态成功且已补绑 reservation。 */
        COMMAND_SUCCEEDED,
        /** 命令终态失败且已推进失败释放。 */
        COMMAND_FAILED,
        /** 命令仍处理中或不存在，只能保持 PREPARED。 */
        COMMAND_PENDING,
        /** 成功终态缺少订单号，属于订单服务数据异常。 */
        COMMAND_INVALID_SUCCEEDED,
        /** 查询命令服务未获得有效权威结果。 */
        COMMAND_QUERY_FAILED,
        /** reservation 缺少命令或用户归属键，不能自动查询。 */
        MISSING_RECONCILE_KEY,
        /** 失败释放和迟到成功命令冲突，必须人工裁决。 */
        TERMINAL_CONFLICT,
        /** 远程查询、绑定、释放或回滚执行抛出异常。 */
        EXECUTION_FAILED
    }

    /** 可由数据库快照统计的固定积压状态。 */
    public enum BacklogState {
        /** 已绑定订单仍有资源释放步骤未完成。 */
        BOUND_INCOMPLETE,
        /** PREPARED reservation 等待订单命令给出权威终态。 */
        PREPARED_PENDING,
        /** 已领取失败释放权但资源步骤或终态尚未完成。 */
        RELEASING,
        /** PREPARED reservation 缺少自动对账所需命令或用户归属键。 */
        PREPARED_MISSING_KEY
    }
}
