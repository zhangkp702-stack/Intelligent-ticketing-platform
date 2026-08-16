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
import static org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketSeatReservationRecoveryMetrics.RecoveryFlow.PREPARED_COMMAND;
import static org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketSeatReservationRecoveryMetrics.RecoveryOutcome.TERMINAL_CONFLICT;

/**
 * 验证座位占用恢复指标使用固定低基数标签，并正确暴露积压快照。
 */
class TicketSeatReservationRecoveryMetricsTests {

    /**
     * 记录恢复结果和数据库积压后，Prometheus 所需的 Counter、Timer 与 Gauge 应可按固定标签查询。
     */
    @Test
    void recordsRecoveryOutcomeAndBacklogGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketSeatReservationRecoveryMetrics metrics = new TicketSeatReservationRecoveryMetrics(registry);

        // 终态冲突属于人工裁决事件，必须与可自动收敛的恢复结果分开统计。
        metrics.recordRecovery(PREPARED_COMMAND, TERMINAL_CONFLICT, System.nanoTime());
        metrics.refreshBacklog(3, 2, 1, 4);

        assertThat(registry.get("index12306.ticket.reservation.recovery.attempts")
                .tags("flow", "PREPARED_COMMAND", "outcome", "TERMINAL_CONFLICT")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get("index12306.ticket.reservation.recovery.duration")
                .tags("flow", "PREPARED_COMMAND", "outcome", "TERMINAL_CONFLICT")
                .timer()
                .count()).isEqualTo(1);
        assertThat(registry.get("index12306.ticket.reservation.recovery.backlog")
                .tag("state", "PREPARED_MISSING_KEY")
                .gauge()
                .value()).isEqualTo(4);
        assertThat(registry.get("index12306.ticket.reservation.recovery.backlog")
                .tag("state", "BOUND_INCOMPLETE")
                .gauge()
                .value()).isEqualTo(3);
    }

    /**
     * 积压采集异常不能覆盖上轮成功快照，并必须增加独立失败计数。
     */
    @Test
    void preservesGaugeAndRecordsRefreshFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketSeatReservationRecoveryMetrics metrics = new TicketSeatReservationRecoveryMetrics(registry);

        // 失败由采集器记录，不向 Gauge 写入伪造的零值。
        metrics.refreshBacklog(2, 0, 0, 0);
        metrics.recordBacklogRefreshFailure();

        assertThat(registry.get("index12306.ticket.reservation.recovery.backlog")
                .tag("state", "BOUND_INCOMPLETE")
                .gauge()
                .value()).isEqualTo(2);
        assertThat(registry.get("index12306.ticket.reservation.recovery.backlog.refresh.failures")
                .counter()
                .count()).isEqualTo(1);
    }
}
