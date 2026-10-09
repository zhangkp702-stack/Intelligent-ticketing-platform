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
 * 记录 Redis 临时占座租约扫描的结果、耗时和单轮过期积压快照。
 *
 * <p>指标标签只使用固定枚举；reservationId 和 Redis key 仅保留在结构化日志中，避免时序基数失控。</p>
 */
@Component
public class RedisSeatLeaseRecoveryMetrics {

    private static final String RECOVERY_ATTEMPT_METRIC = "index12306.ticket.redis.seat.lease.recovery.attempts";
    private static final String RECOVERY_DURATION_METRIC = "index12306.ticket.redis.seat.lease.recovery.duration";
    private static final String EXPIRED_BATCH_METRIC = "index12306.ticket.redis.seat.lease.expired.batch";
    private static final String SCAN_FAILURE_METRIC = "index12306.ticket.redis.seat.lease.scan.failures";

    private final MeterRegistry meterRegistry;
    private final AtomicLong expiredLeaseBatch = new AtomicLong();

    /**
     * 创建租约恢复指标，并注册最近一次过期租约批次大小 Gauge。
     *
     * @param meterRegistry 应用指标注册表
     */
    public RedisSeatLeaseRecoveryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder(EXPIRED_BATCH_METRIC, expiredLeaseBatch, AtomicLong::doubleValue)
                .description("Expired Redis seat leases discovered in the latest scan batch")
                .register(meterRegistry);
    }

    /**
     * 记录扫描器本轮发现的过期租约数量。
     *
     * @param count 已读取的过期租约数量
     */
    public void refreshExpiredLeaseBatch(long count) {
        // 正常情况下该值低于批次上限；持续等于上限说明扫描器可能追不上积压增长。
        expiredLeaseBatch.set(Math.max(0L, count));
    }

    /**
     * 记录一条过期租约的处理结果与耗时。
     *
     * @param outcome 固定的处理结果
     * @param startedNanos 租约处理开始的单调时钟
     */
    public void recordRecovery(RecoveryOutcome outcome, long startedNanos) {
        Counter.builder(RECOVERY_ATTEMPT_METRIC)
                .description("Redis seat lease recovery attempts")
                .tag("outcome", outcome.name())
                .register(meterRegistry)
                .increment();
        Timer.builder(RECOVERY_DURATION_METRIC)
                .description("Redis seat lease recovery duration")
                .tag("outcome", outcome.name())
                .register(meterRegistry)
                .record(Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)));
    }

    /**
     * 记录一次 Redis 租约索引扫描失败。
     */
    public void recordScanFailure() {
        Counter.builder(SCAN_FAILURE_METRIC)
                .description("Redis seat lease scan failures")
                .register(meterRegistry)
                .increment();
    }

    /** Redis 过期租约的固定处理结果。 */
    public enum RecoveryOutcome {
        /** 数据库已提交 PREPARED 或 BOUND，只移除临时租约索引。 */
        CONFIRMED,
        /** 数据库尚未提交，按 Redis owner 精确释放并触发预备阶段补偿。 */
        RELEASED,
        /** Redis、数据库或补偿链路执行异常，等待下一轮重试。 */
        FAILED
    }
}
