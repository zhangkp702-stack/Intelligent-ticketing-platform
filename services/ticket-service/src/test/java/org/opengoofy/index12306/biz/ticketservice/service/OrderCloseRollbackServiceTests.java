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

package org.opengoofy.index12306.biz.ticketservice.service;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandClaim;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandMode;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandService;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 验证订单关闭回滚只由成功认领可靠命令的线程执行。
 */
class OrderCloseRollbackServiceTests {

    /**
     * 已成功或正在执行的相同订单不应再次查询订单或修改库存。
     */
    @Test
    void skipsInventoryRollbackWhenCommandWasAlreadyClaimed() {
        TicketSeatReservationReleaseService reservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        ReliableCommandService reliableCommandService = mock(ReliableCommandService.class);
        ReliableCommandRecord record = record(ReliableCommandStatus.SUCCEEDED, false);
        when(reliableCommandService.claim(any())).thenReturn(
                new ReliableCommandClaim(ReliableCommandClaim.Outcome.REPLAY_SUCCEEDED, record));
        OrderCloseRollbackService service = new OrderCloseRollbackService(reservationReleaseService, reliableCommandService);

        service.rollback("order-1");

        verify(reservationReleaseService, never()).releaseOrder(any());
    }

    /**
     * 首次成功认领后必须执行一次座位释放和缓存回滚，并持久化成功状态。
     */
    @Test
    void rollsBackInventoryAndMarksCommandSucceededAfterAcquiringClaim() {
        TicketSeatReservationReleaseService reservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        ReliableCommandService reliableCommandService = mock(ReliableCommandService.class);
        ReliableCommandRecord record = record(ReliableCommandStatus.PROCESSING, true);
        when(reliableCommandService.claim(any())).thenReturn(
                new ReliableCommandClaim(ReliableCommandClaim.Outcome.ACQUIRED, record));
        when(reliableCommandService.markSucceeded(record, "true", "order-1")).thenReturn(true);
        OrderCloseRollbackService service = new OrderCloseRollbackService(reservationReleaseService, reliableCommandService);

        service.rollback("order-1");

        verify(reservationReleaseService).releaseOrder("order-1");
        verify(reliableCommandService).markSucceeded(record, "true", "order-1");
        verify(reliableCommandService).release(record);
    }

    /**
     * 关闭回滚多次恢复失败后必须停止自动重试，并把可靠命令转为人工处理状态。
     */
    @Test
    void marksRollbackForManualReviewWhenReconciliationAttemptsAreExhausted() {
        TicketSeatReservationReleaseService reservationReleaseService = mock(TicketSeatReservationReleaseService.class);
        ReliableCommandService reliableCommandService = mock(ReliableCommandService.class);
        ReliableCommandRecord record = record(ReliableCommandStatus.UNKNOWN, true, 1);
        when(reliableCommandService.findDueReconciliations(anyString(), anyInt())).thenReturn(List.of(record));
        when(reliableCommandService.claimReconciliation(eq(record.key()), any())).thenReturn(Optional.of(record));
        doThrow(new ServiceException("redis unavailable")).when(reservationReleaseService).releaseOrder("order-1");
        OrderCloseRollbackService service = new OrderCloseRollbackService(reservationReleaseService, reliableCommandService);
        ReflectionTestUtils.setField(service, "recoveryMaxAttempts", 1);

        service.recoverDueRollbacks();

        verify(reliableCommandService).finishReconciliation(eq(record), eq(ReliableCommandStatus.MANUAL_REVIEW),
                eq("ROLLBACK_RETRY_EXHAUSTED"), anyString(), eq("ROLLBACK_RETRY_ERROR"), isNull());
    }

    /**
     * 构造带或不带有效租约的可靠命令记录。
     *
     * @param status 当前持久化状态
     * @param leased 是否包含当前执行租约
     * @return 测试使用的可靠命令记录
     */
    private ReliableCommandRecord record(ReliableCommandStatus status, boolean leased) {
        return record(status, leased, 0);
    }

    /**
     * 构造指定对账次数的可靠命令记录，用于验证自动重试与人工处理边界。
     *
     * @param status 当前可靠命令状态
     * @param leased 是否包含当前执行租约
     * @param reconcileAttemptCount 已执行的对账次数
     * @return 测试用可靠命令记录
     */
    private ReliableCommandRecord record(ReliableCommandStatus status, boolean leased, int reconcileAttemptCount) {
        Instant now = Instant.EPOCH;
        ReliableCommandLease lease = leased
                ? new ReliableCommandLease("worker-1", now.plusSeconds(120), 1L)
                : null;
        return new ReliableCommandRecord(
                new ReliableCommandKey("ticket-order-close-rollback", "order-1", "order-1"),
                "ORDER_CLOSE_ROLLBACK", ReliableCommandMode.REMOTE_EFFECT,
                "ticket-order-close", "order-1", "ticket-close-v1", status,
                null, null, null, "order-1", lease == null ? null : lease.owner(),
                lease == null ? null : lease.until(), lease == null ? 1L : lease.fencingToken(),
                now, 1, now, reconcileAttemptCount, now, now);
    }
}
