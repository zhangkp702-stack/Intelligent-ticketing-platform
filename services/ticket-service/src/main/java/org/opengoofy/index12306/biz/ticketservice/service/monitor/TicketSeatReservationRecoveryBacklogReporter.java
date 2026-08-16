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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TicketSeatReservationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 定时读取超时 reservation 的低频状态快照，供 Prometheus 发现持续积压和人工核对项。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketSeatReservationRecoveryBacklogReporter {

    private final TicketSeatReservationMapper ticketSeatReservationMapper;
    private final TicketSeatReservationRecoveryMetrics metrics;

    @Value("${index12306.ticket.reservation-recovery.timeout-millis:60000}")
    private long reservationRecoveryTimeoutMillis;

    /**
     * 刷新恢复积压 Gauge；读取失败时保留上一轮值并记录采集失败。
     */
    @Scheduled(fixedDelayString = "${index12306.ticket.reservation-recovery.backlog-refresh-interval-millis:15000}")
    public void refresh() {
        try {
            // 与恢复扫描共用超时宽限期，只统计本应已进入恢复范围的持久化记录。
            Date deadline = new Date(System.currentTimeMillis() - Math.max(1L, reservationRecoveryTimeoutMillis));
            metrics.refreshBacklog(
                    ticketSeatReservationMapper.countStaleIncompleteReservations(deadline),
                    ticketSeatReservationMapper.countStalePreparedReservations(deadline),
                    ticketSeatReservationMapper.countStaleReleasingReservations(deadline),
                    ticketSeatReservationMapper.countStalePreparedReservationsMissingReconcileKey(deadline));
        } catch (RuntimeException exception) {
            // 不将数据库短暂故障误报为零积压，告警可结合失败计数判断快照是否已过期。
            metrics.recordBacklogRefreshFailure();
            log.warn("ticket_reservation_recovery_backlog_refresh_failed exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
