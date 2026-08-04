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

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.BusinessOperationDO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationLeaseService.OperationLease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Agent V2 购票操作的认领、结果重放和失败固化语义。
 */
class PurchaseOperationServiceTests {

    private TicketService ticketService;
    private BusinessOperationTransactionService transactionService;
    private BusinessOperationLeaseService leaseService;
    private PurchaseOperationService purchaseOperationService;

    /**
     * 创建隔离的购票依赖并设置当前用户上下文。
     */
    @BeforeEach
    void setUp() {
        // 每个用例使用独立 Mock，避免操作认领状态在测试之间泄漏。
        ticketService = mock(TicketService.class);
        transactionService = mock(BusinessOperationTransactionService.class);
        leaseService = mock(BusinessOperationLeaseService.class);
        when(leaseService.create(anyString())).thenAnswer(invocation -> new OperationLease(
                invocation.getArgument(0), "worker-1", 1L, new Date(0), new Date(120000)));
        purchaseOperationService = new PurchaseOperationService(
                ticketService,
                new BusinessOperationCoordinator(transactionService, leaseService));
        UserContext.setUser(UserInfoDTO.builder()
                .userId("user-1")
                .username("alice")
                .build());
    }

    /**
     * 清理线程本地用户，避免影响后续测试。
     */
    @AfterEach
    void tearDown() {
        // UserContext 使用线程本地变量，测试结束必须显式释放。
        UserContext.removeUser();
    }

    /**
     * 验证普通客户端未提供 operationId 时保持原 V2 调用行为。
     */
    @Test
    void delegatesOrdinaryPurchaseWithoutPersistentClaim() {
        PurchaseTicketReqDTO request = request(null);
        TicketPurchaseRespDTO expected = result("order-normal");
        when(ticketService.purchaseTicketsV2(request)).thenReturn(expected);

        // 普通请求不创建 Agent 专用幂等记录。
        TicketPurchaseRespDTO actual = purchaseOperationService.purchaseTicketsV2(request);

        assertThat(actual).isSameAs(expected);
        verify(transactionService, never()).tryClaim(any());
    }

    /**
     * 验证首次 Agent 操作成功认领后只执行一次真实购票并保存结果。
     */
    @Test
    void claimsOperationBeforePurchaseAndPersistsSuccess() {
        PurchaseTicketReqDTO request = request(" action-1 ");
        TicketPurchaseRespDTO expected = result("order-1");
        when(transactionService.tryClaim(any())).thenReturn(true);
        when(ticketService.purchaseTicketsV2(request)).thenReturn(expected);

        // 操作标识会先规范化，再进入真实 V2 购票链。
        TicketPurchaseRespDTO actual = purchaseOperationService.purchaseTicketsV2(request);

        assertThat(actual).isSameAs(expected);
        verify(transactionService).markSucceeded(
                "action-1", "worker-1", 1L, JSON.toJSONString(expected), "order-1");
        verify(ticketService).purchaseTicketsV2(request);
    }

    /**
     * 验证重复成功请求直接返回持久化结果，不再次扣票。
     */
    @Test
    void replaysPersistedResultForDuplicateSuccessfulOperation() {
        PurchaseTicketReqDTO request = request("action-1");
        TicketPurchaseRespDTO expected = result("order-1");
        AtomicReference<BusinessOperationDO> attempted = new AtomicReference<>();
        when(transactionService.tryClaim(any())).thenAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            return false;
        });
        when(transactionService.findById("action-1")).thenAnswer(ignored -> BusinessOperationDO.builder()
                .operationId("action-1")
                .operationType("PURCHASE_TICKET")
                .userId("user-1")
                .requestFingerprint(attempted.get().getRequestFingerprint())
                .status(BusinessOperationTransactionService.STATUS_SUCCEEDED)
                .resultJson(JSON.toJSONString(expected))
                .build());

        // 重复请求读取首次成功结果，不进入票务扣减链。
        TicketPurchaseRespDTO actual = purchaseOperationService.purchaseTicketsV2(request);

        assertThat(actual.getOrderSn()).isEqualTo("order-1");
        verify(ticketService, never()).purchaseTicketsV2(any());
    }

    /**
     * 验证 operationId 不能被复用于另一份购票参数。
     */
    @Test
    void rejectsOperationIdReusedWithDifferentPayload() {
        PurchaseTicketReqDTO request = request("action-1");
        when(transactionService.tryClaim(any())).thenReturn(false);
        when(transactionService.findById("action-1")).thenReturn(BusinessOperationDO.builder()
                .operationId("action-1")
                .operationType("PURCHASE_TICKET")
                .userId("user-1")
                .requestFingerprint("different-fingerprint")
                .status(BusinessOperationTransactionService.STATUS_SUCCEEDED)
                .resultJson(JSON.toJSONString(result("order-1")))
                .build());

        // 参数摘要不一致时拒绝重放，不能把旧订单当作本次结果。
        assertThatThrownBy(() -> purchaseOperationService.purchaseTicketsV2(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("与原请求不一致");
        verify(ticketService, never()).purchaseTicketsV2(any());
    }

    /**
     * 验证真实购票失败后记录失败终态并保留原异常。
     */
    @Test
    void persistsFailedStateWithoutReplacingBusinessException() {
        PurchaseTicketReqDTO request = request("action-1");
        ServiceException failure = new ServiceException("列车站点已无余票");
        when(transactionService.tryClaim(any())).thenReturn(true);
        when(ticketService.purchaseTicketsV2(request)).thenThrow(failure);

        // 相同操作标识后续不能再次进入扣票链，调用方需要创建新的操作。
        assertThatThrownBy(() -> purchaseOperationService.purchaseTicketsV2(request))
                .isSameAs(failure);
        verify(transactionService).markUnknown(
                "action-1", "worker-1", 1L, "列车站点已无余票", "DOWNSTREAM_RESULT_UNKNOWN");
        verify(transactionService, never()).markSucceeded(
                anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class));
    }

    /**
     * 构造包含稳定购票参数的测试请求。
     *
     * @param operationId 可选操作标识
     * @return 购票请求
     */
    private PurchaseTicketReqDTO request(String operationId) {
        PurchaseTicketPassengerDetailDTO passenger = new PurchaseTicketPassengerDetailDTO();
        passenger.setPassengerId("passenger-1");
        passenger.setSeatType(3);
        PurchaseTicketReqDTO request = new PurchaseTicketReqDTO();
        request.setOperationId(operationId);
        request.setTrainId("train-1");
        request.setDepartureDate(new Date(1785283200000L));
        request.setPassengers(List.of(passenger));
        request.setChooseSeats(List.of("A"));
        request.setDeparture("北京南");
        request.setArrival("上海虹桥");
        return request;
    }

    /**
     * 构造只包含订单号的购票结果。
     *
     * @param orderSn 订单号
     * @return 购票结果
     */
    private TicketPurchaseRespDTO result(String orderSn) {
        return TicketPurchaseRespDTO.builder()
                .orderSn(orderSn)
                .ticketOrderDetails(List.of())
                .build();
    }
}
