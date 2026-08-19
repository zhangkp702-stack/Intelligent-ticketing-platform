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
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import static org.opengoofy.index12306.biz.ticketservice.service.TicketSeatReservationReleaseService.ORDER_CREATION_EVENT_NAMESPACE;

/**
 * 记录异步建单执行器和本地 Outbox 的低基数指标，支持识别积压、拒绝和重试。
 *
 * <p>指标不携带 reservationId、订单号或用户标识；详细关联仍由结构化日志完成。</p>
 */
@Component
public class TicketOrderCreationMetrics {

    private static final String EXECUTOR_ACTIVE_METRIC = "index12306.ticket.async.order.executor.active";
    private static final String EXECUTOR_POOL_SIZE_METRIC = "index12306.ticket.async.order.executor.pool.size";
    private static final String EXECUTOR_QUEUE_SIZE_METRIC = "index12306.ticket.async.order.executor.queue.size";
    private static final String EXECUTOR_QUEUE_REMAINING_METRIC = "index12306.ticket.async.order.executor.queue.remaining";
    private static final String EXECUTOR_COMPLETED_METRIC = "index12306.ticket.async.order.executor.completed";
    private static final String EXECUTOR_REJECTED_METRIC = "index12306.ticket.async.order.executor.rejected";
    private static final String OUTBOX_BACKLOG_METRIC = "index12306.ticket.async.order.outbox.backlog";
    private static final String OUTBOX_BACKLOG_REFRESH_FAILURE_METRIC = "index12306.ticket.async.order.outbox.backlog.refresh.failures";
    private static final String OUTBOX_CLAIMED_METRIC = "index12306.ticket.async.order.outbox.claimed";
    private static final String OUTBOX_LEASE_RECOVERED_METRIC = "index12306.ticket.async.order.outbox.lease.recovered";
    private static final String OUTBOX_DISPATCH_WAIT_METRIC = "index12306.ticket.async.order.outbox.dispatch.wait";
    private static final String OUTBOX_DISPATCH_OUTCOME_METRIC = "index12306.ticket.async.order.outbox.dispatch.outcome";
    private static final String OUTBOX_DISPATCH_CYCLE_FAILURE_METRIC = "index12306.ticket.async.order.outbox.dispatch.cycle.failures";

    private final MeterRegistry meterRegistry;
    private final ReliableEventStore reliableEventStore;
    private final AtomicLong pendingBacklog = new AtomicLong();
    private final AtomicLong publishingBacklog = new AtomicLong();

    /**
     * 创建异步建单指标组件，并注册持久化积压 Gauge。
     *
     * @param meterRegistry 应用指标注册表
     * @param reliableEventStore 本地可靠 Outbox 存储
     */
    public TicketOrderCreationMetrics(MeterRegistry meterRegistry, ReliableEventStore reliableEventStore) {
        this.meterRegistry = meterRegistry;
        this.reliableEventStore = reliableEventStore;
        // Gauge 只读取最近一次快照，避免 Prometheus 抓取时直接压测业务数据库。
        Gauge.builder(OUTBOX_BACKLOG_METRIC, pendingBacklog, AtomicLong::doubleValue)
                .tag("status", ReliableOutboxStatus.PENDING.name())
                .register(meterRegistry);
        Gauge.builder(OUTBOX_BACKLOG_METRIC, publishingBacklog, AtomicLong::doubleValue)
                .tag("status", ReliableOutboxStatus.PUBLISHING.name())
                .register(meterRegistry);
    }

    /**
     * 绑定异步建单执行器的运行态 Gauge。
     *
     * @param executor 已完成初始化的专用线程池
     */
    public void registerExecutor(ThreadPoolTaskExecutor executor) {
        // 线程池在 Bean 初始化后才有底层执行器，直接读取内存状态不会引入额外 I/O。
        ThreadPoolExecutor threadPool = executor.getThreadPoolExecutor();
        Gauge.builder(EXECUTOR_ACTIVE_METRIC, threadPool, ThreadPoolExecutor::getActiveCount).register(meterRegistry);
        Gauge.builder(EXECUTOR_POOL_SIZE_METRIC, threadPool, ThreadPoolExecutor::getPoolSize).register(meterRegistry);
        Gauge.builder(EXECUTOR_QUEUE_SIZE_METRIC, threadPool, value -> value.getQueue().size()).register(meterRegistry);
        Gauge.builder(EXECUTOR_QUEUE_REMAINING_METRIC, threadPool,
                value -> value.getQueue().remainingCapacity()).register(meterRegistry);
        Gauge.builder(EXECUTOR_COMPLETED_METRIC, threadPool, ThreadPoolExecutor::getCompletedTaskCount)
                .register(meterRegistry);
    }

    /**
     * 刷新异步建单 Outbox 的持久化积压快照。
     */
    @Scheduled(fixedDelayString = "${index12306.ticket.async-order.backlog-refresh-interval-millis:5000}")
    public void refreshOutboxBacklog() {
        try {
            // 仅按现有 namespace 与 status 索引统计，不读取事件载荷。
            pendingBacklog.set(reliableEventStore.countEventsByStatus(
                    ORDER_CREATION_EVENT_NAMESPACE, ReliableOutboxStatus.PENDING));
            publishingBacklog.set(reliableEventStore.countEventsByStatus(
                    ORDER_CREATION_EVENT_NAMESPACE, ReliableOutboxStatus.PUBLISHING));
        } catch (RuntimeException ex) {
            // 保留上一轮有效快照，单独记录采集失败以区分真实零积压。
            Counter.builder(OUTBOX_BACKLOG_REFRESH_FAILURE_METRIC).register(meterRegistry).increment();
        }
    }

    /**
     * 记录一次执行器拒绝，供容量告警直接使用。
     */
    public void recordExecutorRejected() {
        // 拒绝不应该由 CallerRunsPolicy 隐藏，否则调度线程会被远程调用反压阻塞。
        Counter.builder(EXECUTOR_REJECTED_METRIC).register(meterRegistry).increment();
    }

    /**
     * 记录一次成功认领的 Outbox 数量。
     *
     * @param claimedCount 本轮取得租约的事件数量
     */
    public void recordClaimed(int claimedCount) {
        // Counter 只累加正数，空批次不创建虚假的处理速率。
        if (claimedCount > 0) {
            Counter.builder(OUTBOX_CLAIMED_METRIC).register(meterRegistry).increment(claimedCount);
        }
    }

    /**
     * 记录一次因实例宕机或任务遗失而回收的发布租约数量。
     *
     * @param recoveredCount 本轮恢复的事件数量
     */
    public void recordLeaseRecovered(int recoveredCount) {
        // 租约恢复次数升高说明服务中断或调度资源不足，需要结合拒绝数与积压共同判断。
        if (recoveredCount > 0) {
            Counter.builder(OUTBOX_LEASE_RECOVERED_METRIC).register(meterRegistry).increment(recoveredCount);
        }
    }

    /**
     * 记录事件从本地事务写入到被异步调度领取之间的等待时间。
     *
     * @param createdAt Outbox 初次持久化时间
     */
    public void recordDispatchWait(Instant createdAt) {
        // 使用事件创建时间计算排队等待，能直接观察 Outbox 是否持续积压。
        Duration wait = Duration.between(createdAt, Instant.now());
        Timer.builder(OUTBOX_DISPATCH_WAIT_METRIC)
                .register(meterRegistry)
                .record(wait.isNegative() ? Duration.ZERO : wait);
    }

    /**
     * 记录一次异步建单派发结果。
     *
     * @param outcome 固定的派发结果分类
     */
    public void recordDispatchOutcome(DispatchOutcome outcome) {
        // 结果标签来自枚举，避免失败信息、订单号等动态内容造成监控高基数。
        Counter.builder(OUTBOX_DISPATCH_OUTCOME_METRIC)
                .tag("outcome", outcome.name())
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录一次 Outbox 扫描、租约恢复或认领周期失败。
     */
    public void recordDispatchCycleFailure() {
        // 周期失败与单事件远程建单失败分开统计，前者通常意味着迁移、数据库或存储层异常。
        Counter.builder(OUTBOX_DISPATCH_CYCLE_FAILURE_METRIC).register(meterRegistry).increment();
    }

    /** 异步建单派发的固定结果分类。 */
    public enum DispatchOutcome {
        /** 远程订单创建及本地状态绑定均完成。 */
        PUBLISHED,
        /** 当前状态已由对账链路收敛，无需再次远程建单。 */
        RECONCILED,
        /** 远程建单或本地绑定失败，已安排下一次重试。 */
        RETRY,
        /** 执行器已满，事件已立即退回 Outbox 等待短退避。 */
        EXECUTOR_REJECTED,
        /** 租约被其他实例接管，本实例没有覆盖其处理结果。 */
        FENCE_LOST
    }
}
