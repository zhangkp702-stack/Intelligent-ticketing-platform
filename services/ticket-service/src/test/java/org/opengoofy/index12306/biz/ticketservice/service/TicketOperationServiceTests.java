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
import org.opengoofy.index12306.biz.ticketservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Date;
import org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationLeaseService.OperationLease;
import static org.mockito.ArgumentMatchers.anyString;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Agent 取消订单和退票的操作认领、结果重放及跨服务幂等键语义。
 */
class TicketOperationServiceTests {

    private TicketService ticketService;
    private BusinessOperationTransactionService transactionService;
    private BusinessOperationLeaseService leaseService;
    private TicketOperationService ticketOperationService;

    /**
     * 创建隔离的票务写操作依赖并设置当前用户上下文。
     */
    @BeforeEach
    void setUp() {
        // 每个用例使用独立 Mock，避免操作状态在测试之间泄漏。
        ticketService = mock(TicketService.class);
        transactionService = mock(BusinessOperationTransactionService.class);
        leaseService = mock(BusinessOperationLeaseService.class);
        when(leaseService.create(anyString())).thenAnswer(invocation -> new OperationLease(
                invocation.getArgument(0), "worker-1", 1L, new Date(0), new Date(120000)));
        ticketOperationService = new TicketOperationService(
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
     * 验证普通取消请求未提供 operationId 时保持原调用行为。
     */
    @Test
    void delegatesOrdinaryCancellationWithoutPersistentClaim() {
        CancelTicketOrderReqDTO request = new CancelTicketOrderReqDTO("order-1");

        // 普通客户端不创建 Agent 专用业务操作记录。
        ticketOperationService.cancelTicketOrder(request);

        verify(ticketService).cancelTicketOrder(request);
        verify(transactionService, never()).tryClaim(any());
    }

    /**
     * 验证首次 Agent 取消成功后保存布尔终态。
     */
    @Test
    void claimsCancellationAndPersistsSuccess() {
        CancelTicketOrderReqDTO request = new CancelTicketOrderReqDTO(" action-1 ", "order-1");
        when(transactionService.tryClaim(any())).thenReturn(true);

        // 只有获得 operationId 执行权的请求可以进入真实取消链。
        ticketOperationService.cancelTicketOrder(request);

        verify(ticketService).cancelTicketOrder(request);
        verify(transactionService).markSucceeded(
                "action-1", "worker-1", 1L, "true", "order-1");
    }

    /**
     * 验证重复成功的取消请求不会再次释放订单和座位资源。
     */
    @Test
    void replaysSuccessfulCancellationWithoutSecondExecution() {
        CancelTicketOrderReqDTO request = new CancelTicketOrderReqDTO("action-1", "order-1");
        AtomicReference<BusinessOperationDO> attempted = new AtomicReference<>();
        when(transactionService.tryClaim(any())).thenAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            return false;
        });
        when(transactionService.findById("action-1")).thenAnswer(ignored -> BusinessOperationDO.builder()
                .operationId("action-1")
                .operationType("CANCEL_TICKET_ORDER")
                .userId("user-1")
                .requestFingerprint(attempted.get().getRequestFingerprint())
                .status(BusinessOperationTransactionService.STATUS_SUCCEEDED)
                .resultJson("true")
                .build());

        // 已成功记录直接重放内部布尔结果，不再次调用取消订单服务。
        ticketOperationService.cancelTicketOrder(request);

        verify(ticketService, never()).cancelTicketOrder(any());
    }

    /**
     * 验证 Agent 退票统一使用 actionId 作为票务和支付退款幂等键。
     */
    @Test
    void usesOperationIdAsRefundRequestIdAndPersistsResult() {
        RefundTicketReqDTO request = refundRequest("action-1", null);
        RefundTicketRespDTO expected = refundResult("action-1");
        when(transactionService.tryClaim(any())).thenReturn(true);
        when(ticketService.commonTicketRefund(request)).thenReturn(expected);

        // 票务操作认领前先规范化支付退款请求标识，确保整个调用链使用同一键。
        RefundTicketRespDTO actual = ticketOperationService.refundTicket(request);

        assertThat(actual).isSameAs(expected);
        assertThat(request.getRequestId()).isEqualTo("action-1");
        verify(transactionService).markSucceeded(
                "action-1", "worker-1", 1L, JSON.toJSONString(expected), "order-1");
    }

    /**
     * 验证 Agent 退票不能携带与 operationId 不同的支付退款请求标识。
     */
    @Test
    void rejectsDifferentRefundRequestId() {
        RefundTicketReqDTO request = refundRequest("action-1", "request-2");

        // 两层幂等键不一致会破坏跨服务重放语义，因此在真实退款前直接拒绝。
        assertThatThrownBy(() -> ticketOperationService.refundTicket(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("标识不一致");
        verify(ticketService, never()).commonTicketRefund(any());
        verify(transactionService, never()).tryClaim(any());
    }

    /**
     * 构造固定退票范围。
     *
     * @param operationId Agent 操作标识
     * @param requestId 支付退款请求标识
     * @return 退票请求
     */
    private RefundTicketReqDTO refundRequest(
            String operationId,
            String requestId) {
        RefundTicketReqDTO request = new RefundTicketReqDTO();
        request.setOperationId(operationId);
        request.setRequestId(requestId);
        request.setOrderSn("order-1");
        request.setType(0);
        request.setSubOrderRecordIdReqList(List.of("item-2", "item-1"));
        return request;
    }

    /**
     * 构造可持久化的退款结果。
     *
     * @param requestId 退款请求标识
     * @return 退款结果
     */
    private RefundTicketRespDTO refundResult(String requestId) {
        RefundTicketRespDTO result = new RefundTicketRespDTO();
        result.setRequestId(requestId);
        result.setOrderSn("order-1");
        result.setType(0);
        result.setRefundAmount(100);
        result.setStatus(1);
        return result;
    }
}
