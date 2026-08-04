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
import org.opengoofy.index12306.biz.ticketservice.dao.entity.BusinessOperationDO;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.OrderCommandStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.impl.BusinessOperationRecoveryServiceImpl;
import org.opengoofy.index12306.framework.starter.convention.result.Result;

import java.util.List;

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
        BusinessOperationTransactionService transactionService = mock(BusinessOperationTransactionService.class);
        BusinessOperationCoordinator coordinator = mock(BusinessOperationCoordinator.class);
        TicketOrderRemoteService orderRemoteService = mock(TicketOrderRemoteService.class);
        PayRemoteService payRemoteService = mock(PayRemoteService.class);
        BusinessOperationDO operation = BusinessOperationDO.builder()
                .operationId("action-1")
                .operationType("PURCHASE_TICKET")
                .userId("user-1")
                .status(BusinessOperationTransactionService.STATUS_UNKNOWN)
                .reconcileAttemptCount(1)
                .build();
        when(transactionService.findDueReconciliations(any())).thenReturn(List.of(operation));
        when(transactionService.claimReconciliation(eq("action-1"), any(), any(), any())).thenReturn(operation);
        OrderCommandStatusRespDTO commandStatus = new OrderCommandStatusRespDTO();
        commandStatus.setStatus("SUCCEEDED");
        commandStatus.setOrderSn("order-1");
        when(orderRemoteService.queryCommandStatus("action-1:create-order"))
                .thenReturn(new Result<OrderCommandStatusRespDTO>()
                        .setCode(Result.SUCCESS_CODE)
                        .setData(commandStatus));
        BusinessOperationRecoveryService recoveryService = new BusinessOperationRecoveryServiceImpl(
                transactionService, coordinator, orderRemoteService, payRemoteService,
                5, 30000, 120000);

        // 恢复器只调用命令查询，并把包含空白名单车票数组的安全结果写回。
        recoveryService.recoverDueOperations();

        verify(orderRemoteService).queryCommandStatus("action-1:create-order");
        verify(transactionService).reconcileSucceeded(
                eq("action-1"),
                eq("{\"orderSn\":\"order-1\",\"ticketOrderDetails\":[]}"),
                eq("order-1"),
                any(),
                eq("ORDER_COMMAND:SUCCEEDED"));
    }

    /**
     * 验证下游尚无命令事实时只安排下一次查询，不调用任何写接口。
     */
    @Test
    void keepsUnknownWhenOrderCommandIsNotFound() {
        BusinessOperationTransactionService transactionService = mock(BusinessOperationTransactionService.class);
        BusinessOperationCoordinator coordinator = mock(BusinessOperationCoordinator.class);
        TicketOrderRemoteService orderRemoteService = mock(TicketOrderRemoteService.class);
        PayRemoteService payRemoteService = mock(PayRemoteService.class);
        BusinessOperationDO operation = BusinessOperationDO.builder()
                .operationId("action-1")
                .operationType("PURCHASE_TICKET")
                .userId("user-1")
                .status(BusinessOperationTransactionService.STATUS_UNKNOWN)
                .reconcileAttemptCount(1)
                .build();
        when(transactionService.findDueReconciliations(any())).thenReturn(List.of(operation));
        when(transactionService.claimReconciliation(eq("action-1"), any(), any(), any())).thenReturn(operation);
        OrderCommandStatusRespDTO commandStatus = new OrderCommandStatusRespDTO();
        commandStatus.setStatus("NOT_FOUND");
        when(orderRemoteService.queryCommandStatus("action-1:create-order"))
                .thenReturn(new Result<OrderCommandStatusRespDTO>()
                        .setCode(Result.SUCCESS_CODE)
                        .setData(commandStatus));
        BusinessOperationRecoveryService recoveryService = new BusinessOperationRecoveryServiceImpl(
                transactionService, coordinator, orderRemoteService, payRemoteService,
                5, 30000, 120000);

        // NOT_FOUND 只是当前未发现成功事实，恢复器继续只读查询而不会创建新订单。
        recoveryService.recoverDueOperations();

        verify(transactionService).reconciliationPending(
                eq(operation), any(), eq("ORDER_COMMAND:NOT_FOUND"), any(), eq(5));
    }
}
