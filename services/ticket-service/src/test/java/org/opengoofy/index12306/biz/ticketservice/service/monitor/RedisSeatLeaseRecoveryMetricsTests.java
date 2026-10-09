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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.opengoofy.index12306.biz.ticketservice.service.monitor.RedisSeatLeaseRecoveryMetrics.RecoveryOutcome.CONFIRMED;

/**
 * 验证 Redis 座位租约恢复指标使用固定标签并暴露扫描积压快照。
 */
class RedisSeatLeaseRecoveryMetricsTests {

    /**
     * 处理成功后必须写入结果 Counter、Timer 和最近一次过期批次 Gauge。
     */
    @Test
    void recordsRecoveryOutcomeAndExpiredLeaseBatch() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisSeatLeaseRecoveryMetrics metrics = new RedisSeatLeaseRecoveryMetrics(registry);

        metrics.refreshExpiredLeaseBatch(7);
        metrics.recordRecovery(CONFIRMED, System.nanoTime());

        assertThat(registry.find("index12306.ticket.redis.seat.lease.expired.batch").gauge().value()).isEqualTo(7.0D);
        assertThat(registry.find("index12306.ticket.redis.seat.lease.recovery.attempts")
                .tag("outcome", "CONFIRMED").counter().count()).isEqualTo(1.0D);
        assertThat(registry.find("index12306.ticket.redis.seat.lease.recovery.duration")
                .tag("outcome", "CONFIRMED").timer().count()).isEqualTo(1L);
    }

    /**
     * 扫描 Redis 索引失败时必须单独累加失败计数，不能把它伪装成零积压。
     */
    @Test
    void recordsRedisLeaseScanFailureSeparately() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisSeatLeaseRecoveryMetrics metrics = new RedisSeatLeaseRecoveryMetrics(registry);

        metrics.recordScanFailure();

        assertThat(registry.find("index12306.ticket.redis.seat.lease.scan.failures").counter().count())
                .isEqualTo(1.0D);
    }
}
