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
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.OrderCommandStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.impl.BusinessOperationRecoveryServiceImpl;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandMode;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandService;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证票务业务操作恢复器只查询下游事实并收敛状态。
 */
class BusinessOperationRecoveryServiceTests {

    /**
     * 验证购票响应丢失后通过稳定建单命令恢复原订单号。
     */
    @Test
    void recoversPurchaseFromAuthoritativeOrderCommand() {
        ReliableCommandService reliableCommandService = mock(ReliableCommandService.class);
        BusinessOperationCoordinator coordinator = mock(BusinessOperationCoordinator.class);
        TicketOrderRemoteService orderRemoteService = mock(TicketOrderRemoteService.class);
        PayRemoteService payRemoteService = mock(PayRemoteService.class);
        ReliableCommandRecord candidate = operation(ReliableCommandStatus.UNKNOWN, false);
        ReliableCommandRecord operation = operation(ReliableCommandStatus.RECONCILING, true);
        when(reliableCommandService.findDueReconciliations(
                BusinessOperationCoordinator.COMMAND_NAMESPACE, 100)).thenReturn(List.of(candidate));
        when(reliableCommandService.claimReconciliation(
                eq(candidate.key()), any(Duration.class))).thenReturn(Optional.of(operation));
        when(reliableCommandService.reconcileSucceeded(any(), any(), any(), any())).thenReturn(true);
        OrderCommandStatusRespDTO commandStatus = new OrderCommandStatusRespDTO();
        commandStatus.setStatus("SUCCEEDED");
        commandStatus.setOrderSn("order-1");
        when(orderRemoteService.queryCommandStatus("action-1:create-order"))
                .thenReturn(new Result<OrderCommandStatusRespDTO>()
                        .setCode(Result.SUCCESS_CODE)
                        .setData(commandStatus));
        BusinessOperationRecoveryService recoveryService = new BusinessOperationRecoveryServiceImpl(
                reliableCommandService, coordinator, orderRemoteService, payRemoteService,
                5, 30000, 120000);

        // 恢复器只调用命令查询，并把包含空白名单车票数组的安全结果写回。
        recoveryService.recoverDueOperations();

        verify(orderRemoteService).queryCommandStatus("action-1:create-order");
        verify(reliableCommandService).reconcileSucceeded(
                eq(operation),
                eq("{\"orderSn\":\"order-1\",\"ticketOrderDetails\":[]}"),
                eq("order-1"),
                eq("ORDER_COMMAND:SUCCEEDED"));
    }

    /**
     * 验证下游尚无命令事实时只安排下一次查询，不调用任何写接口。
     */
    @Test
    void keepsUnknownWhenOrderCommandIsNotFound() {
        ReliableCommandService reliableCommandService = mock(ReliableCommandService.class);
        BusinessOperationCoordinator coordinator = mock(BusinessOperationCoordinator.class);
        TicketOrderRemoteService orderRemoteService = mock(TicketOrderRemoteService.class);
        PayRemoteService payRemoteService = mock(PayRemoteService.class);
        ReliableCommandRecord candidate = operation(ReliableCommandStatus.UNKNOWN, false);
        ReliableCommandRecord operation = operation(ReliableCommandStatus.RECONCILING, true);
        when(reliableCommandService.findDueReconciliations(
                BusinessOperationCoordinator.COMMAND_NAMESPACE, 100)).thenReturn(List.of(candidate));
        when(reliableCommandService.claimReconciliation(
                eq(candidate.key()), any(Duration.class))).thenReturn(Optional.of(operation));
        OrderCommandStatusRespDTO commandStatus = new OrderCommandStatusRespDTO();
        commandStatus.setStatus("NOT_FOUND");
        when(orderRemoteService.queryCommandStatus("action-1:create-order"))
                .thenReturn(new Result<OrderCommandStatusRespDTO>()
                        .setCode(Result.SUCCESS_CODE)
                        .setData(commandStatus));
        BusinessOperationRecoveryService recoveryService = new BusinessOperationRecoveryServiceImpl(
                reliableCommandService, coordinator, orderRemoteService, payRemoteService,
                5, 30000, 120000);

        // NOT_FOUND 只是当前未发现成功事实，恢复器继续只读查询而不会创建新订单。
        recoveryService.recoverDueOperations();

        verify(reliableCommandService).finishReconciliation(
                eq(operation),
                eq(ReliableCommandStatus.UNKNOWN),
                eq("RECONCILIATION_PENDING"),
                eq("ORDER_COMMAND:NOT_FOUND"),
                eq("ORDER_COMMAND:NOT_FOUND"),
                any(Instant.class));
    }

    /**
     * 构造购票恢复场景的可靠命令记录。
     *
     * @param status 当前状态
     * @param leased 是否携带对账租约
     * @return 测试命令记录
     */
    private ReliableCommandRecord operation(ReliableCommandStatus status, boolean leased) {
        Instant now = Instant.EPOCH;
        ReliableCommandLease lease = leased
                ? new ReliableCommandLease("reconciler-1", now.plusSeconds(120), 2L)
                : null;
        // 对账次数为 1，保证未达到测试配置的自动对账上限。
        return new ReliableCommandRecord(
                BusinessOperationCoordinator.commandKey("action-1"),
                "PURCHASE_TICKET",
                ReliableCommandMode.REMOTE_EFFECT,
                "user-1",
                "fingerprint-1",
                "ticket-v1",
                status,
                null,
                null,
                null,
                null,
                lease == null ? null : lease.owner(),
                lease == null ? null : lease.until(),
                lease == null ? 1L : lease.fencingToken(),
                now,
                1,
                now,
                1,
                now,
                now);
    }
}
