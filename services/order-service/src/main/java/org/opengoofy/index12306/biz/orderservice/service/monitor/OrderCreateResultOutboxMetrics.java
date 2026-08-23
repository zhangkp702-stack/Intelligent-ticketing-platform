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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记录订单创建结果 Outbox 的积压、投递和执行器容量指标。
 *
 * <p>所有标签均来自固定枚举或状态，业务关联信息仍只写入结构化日志。</p>
 */
@Component
public class OrderCreateResultOutboxMetrics {

    private static final String EXECUTOR_ACTIVE_METRIC = "index12306.order.create.result.executor.active";
    private static final String EXECUTOR_QUEUE_SIZE_METRIC = "index12306.order.create.result.executor.queue.size";
    private static final String EXECUTOR_REJECTED_METRIC = "index12306.order.create.result.executor.rejected";
    private static final String OUTBOX_BACKLOG_METRIC = "index12306.order.create.result.outbox.backlog";
    private static final String OUTBOX_BACKLOG_REFRESH_FAILURE_METRIC = "index12306.order.create.result.outbox.backlog.refresh.failures";
    private static final String OUTBOX_CLAIMED_METRIC = "index12306.order.create.result.outbox.claimed";
    private static final String OUTBOX_LEASE_RECOVERED_METRIC = "index12306.order.create.result.outbox.lease.recovered";
    private static final String OUTBOX_DISPATCH_WAIT_METRIC = "index12306.order.create.result.outbox.dispatch.wait";
    private static final String OUTBOX_DISPATCH_OUTCOME_METRIC = "index12306.order.create.result.outbox.dispatch.outcome";
    private static final String OUTBOX_DISPATCH_CYCLE_FAILURE_METRIC = "index12306.order.create.result.outbox.dispatch.cycle.failures";

    private final MeterRegistry meterRegistry;
    private final ReliableEventStore reliableEventStore;
    private final AtomicLong pendingBacklog = new AtomicLong();
    private final AtomicLong publishingBacklog = new AtomicLong();

    /**
     * 创建结果 Outbox 指标组件并注册持久化积压 Gauge。
     *
     * @param meterRegistry 应用指标注册表
     * @param reliableEventStore 可靠 Outbox 存储
     */
    public OrderCreateResultOutboxMetrics(MeterRegistry meterRegistry, ReliableEventStore reliableEventStore) {
        this.meterRegistry = meterRegistry;
        this.reliableEventStore = reliableEventStore;
        // Gauge 仅读取内存快照，Prometheus 抓取不会额外访问订单数据库。
        Gauge.builder(OUTBOX_BACKLOG_METRIC, pendingBacklog, AtomicLong::doubleValue)
                .tag("status", ReliableOutboxStatus.PENDING.name())
                .register(meterRegistry);
        Gauge.builder(OUTBOX_BACKLOG_METRIC, publishingBacklog, AtomicLong::doubleValue)
                .tag("status", ReliableOutboxStatus.PUBLISHING.name())
                .register(meterRegistry);
    }

    /**
     * 绑定结果投递专用执行器的内存运行态 Gauge。
     *
     * @param executor 已初始化的有界线程池
     */
    public void registerExecutor(ThreadPoolTaskExecutor executor) {
        // 线程池 Gauge 直接读取 JVM 内存状态，不会增加 Outbox 查询负担。
        ThreadPoolExecutor threadPool = executor.getThreadPoolExecutor();
        Gauge.builder(EXECUTOR_ACTIVE_METRIC, threadPool, ThreadPoolExecutor::getActiveCount).register(meterRegistry);
        Gauge.builder(EXECUTOR_QUEUE_SIZE_METRIC, threadPool, value -> value.getQueue().size()).register(meterRegistry);
    }

    /**
     * 定时刷新订单创建结果 Outbox 的持久化积压快照。
     */
    @Scheduled(fixedDelayString = "${index12306.order.create-result.backlog-refresh-interval-millis:5000}")
    public void refreshOutboxBacklog() {
        try {
            // 两个统计均命中 namespace、status 组合索引，不读取大载荷字段。
            pendingBacklog.set(reliableEventStore.countEventsByStatus(
                    TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE, ReliableOutboxStatus.PENDING));
            publishingBacklog.set(reliableEventStore.countEventsByStatus(
                    TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE, ReliableOutboxStatus.PUBLISHING));
        } catch (RuntimeException exception) {
            // 保留最近一次有效值，避免数据库短暂故障被误报为零积压。
            Counter.builder(OUTBOX_BACKLOG_REFRESH_FAILURE_METRIC).register(meterRegistry).increment();
        }
    }

    /**
     * 记录专用执行器拒绝的任务数量。
     */
    public void recordExecutorRejected() {
        // 拒绝由调度器立即回退 Outbox，不能用 CallerRunsPolicy 让调度线程被 Broker 阻塞。
        Counter.builder(EXECUTOR_REJECTED_METRIC).register(meterRegistry).increment();
    }

    /**
     * 记录本轮成功取得发布租约的事件数量。
     *
     * @param claimedCount 已认领事件数量
     */
    public void recordClaimed(int claimedCount) {
        // 空批次不创建虚假的处理速率。
        if (claimedCount > 0) {
            Counter.builder(OUTBOX_CLAIMED_METRIC).register(meterRegistry).increment(claimedCount);
        }
    }

    /**
     * 记录从失效实例回收的发布租约数量。
     *
     * @param recoveredCount 已恢复事件数量
     */
    public void recordLeaseRecovered(int recoveredCount) {
        // 该指标升高时应结合积压和拒绝数判断实例中断或容量不足。
        if (recoveredCount > 0) {
            Counter.builder(OUTBOX_LEASE_RECOVERED_METRIC).register(meterRegistry).increment(recoveredCount);
        }
    }

    /**
     * 记录结果事件从事务提交到开始发送之间的等待时间。
     *
     * @param createdAt Outbox 事件创建时间
     */
    public void recordDispatchWait(Instant createdAt) {
        // 负数仅可能来自时钟漂移，按零处理以保护指标注册表。
        Duration wait = Duration.between(createdAt, Instant.now());
        Timer.builder(OUTBOX_DISPATCH_WAIT_METRIC).register(meterRegistry)
                .record(wait.isNegative() ? Duration.ZERO : wait);
    }

    /**
     * 记录单条结果事件的固定投递结果。
     *
     * @param outcome 固定结果分类
     */
    public void recordDispatchOutcome(DispatchOutcome outcome) {
        // 标签只来自枚举，禁止携带 reservationId、订单号或异常文本。
        Counter.builder(OUTBOX_DISPATCH_OUTCOME_METRIC)
                .tag("outcome", outcome.name())
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录扫描、租约恢复或事件认领周期故障。
     */
    public void recordDispatchCycleFailure() {
        // 周期级错误与单条 Broker 失败分开，便于定位数据库或调度器异常。
        Counter.builder(OUTBOX_DISPATCH_CYCLE_FAILURE_METRIC).register(meterRegistry).increment();
    }

    /** 订单创建结果 Outbox 的固定投递结果分类。 */
    public enum DispatchOutcome {
        /** Broker 已确认结果消息。 */
        PUBLISHED,
        /** Broker 发送失败，事件已安排重试。 */
        RETRY,
        /** 任务未进入执行器，已立即退回 Outbox。 */
        EXECUTOR_REJECTED,
        /** 当前实例已失去发布围栏，未覆盖新实例状态。 */
        FENCE_LOST
    }
}
