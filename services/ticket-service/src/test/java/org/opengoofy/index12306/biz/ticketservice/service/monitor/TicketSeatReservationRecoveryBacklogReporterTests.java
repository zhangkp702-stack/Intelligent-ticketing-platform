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
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketSeatReservationMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证恢复积压采集器使用超时数据库快照刷新 Gauge，并在读库异常时保留上轮状态。
 */
class TicketSeatReservationRecoveryBacklogReporterTests {

    /**
     * 四类超时积压必须写入对应固定状态 Gauge，供规则区分自动恢复与人工核对压力。
     */
    @Test
    void refreshesAllRecoveryBacklogGauges() {
        TicketSeatReservationMapper mapper = mock(TicketSeatReservationMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketSeatReservationRecoveryMetrics metrics = new TicketSeatReservationRecoveryMetrics(registry);
        when(mapper.countStaleIncompleteReservations(any(Date.class))).thenReturn(3L);
        when(mapper.countStalePreparedReservations(any(Date.class))).thenReturn(2L);
        when(mapper.countStaleReleasingReservations(any(Date.class))).thenReturn(1L);
        when(mapper.countStalePreparedReservationsMissingReconcileKey(any(Date.class))).thenReturn(4L);
        TicketSeatReservationRecoveryBacklogReporter reporter = reporter(mapper, metrics);

        reporter.refresh();

        assertThat(registry.get("index12306.ticket.reservation.recovery.backlog")
                .tag("state", "BOUND_INCOMPLETE").gauge().value()).isEqualTo(3);
        assertThat(registry.get("index12306.ticket.reservation.recovery.backlog")
                .tag("state", "PREPARED_PENDING").gauge().value()).isEqualTo(2);
        assertThat(registry.get("index12306.ticket.reservation.recovery.backlog")
                .tag("state", "RELEASING").gauge().value()).isEqualTo(1);
        assertThat(registry.get("index12306.ticket.reservation.recovery.backlog")
                .tag("state", "PREPARED_MISSING_KEY").gauge().value()).isEqualTo(4);
    }

    /**
     * 数据库快照读取异常时，采集器必须保留已有 Gauge，并增加刷新失败计数。
     */
    @Test
    void keepsPreviousGaugeWhenBacklogQueryFails() {
        TicketSeatReservationMapper mapper = mock(TicketSeatReservationMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketSeatReservationRecoveryMetrics metrics = new TicketSeatReservationRecoveryMetrics(registry);
        metrics.refreshBacklog(2, 0, 0, 0);
        when(mapper.countStaleIncompleteReservations(any(Date.class))).thenThrow(new IllegalStateException("database unavailable"));
        TicketSeatReservationRecoveryBacklogReporter reporter = reporter(mapper, metrics);

        reporter.refresh();

        assertThat(registry.get("index12306.ticket.reservation.recovery.backlog")
                .tag("state", "BOUND_INCOMPLETE").gauge().value()).isEqualTo(2);
        assertThat(registry.get("index12306.ticket.reservation.recovery.backlog.refresh.failures")
                .counter().count()).isEqualTo(1);
    }

    /**
     * 构造使用固定恢复宽限期的积压采集器，避免测试依赖外部配置。
     *
     * @param mapper reservation 持久层 mock
     * @param metrics 恢复指标记录器
     * @return 可直接执行快照刷新的采集器
     */
    private TicketSeatReservationRecoveryBacklogReporter reporter(TicketSeatReservationMapper mapper,
                                                                    TicketSeatReservationRecoveryMetrics metrics) {
        TicketSeatReservationRecoveryBacklogReporter reporter = new TicketSeatReservationRecoveryBacklogReporter(mapper, metrics);
        // 使用固定宽限期确保所有 mapper 调用都带有恢复截止时间。
        ReflectionTestUtils.setField(reporter, "reservationRecoveryTimeoutMillis", 60_000L);
        return reporter;
    }
}
