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
import org.opengoofy.index12306.biz.ticketservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandClaim;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandDefinition;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandMode;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandService;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;

import java.time.Instant;
import java.util.List;
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
    private ReliableCommandService reliableCommandService;
    private TicketOperationService ticketOperationService;

    /**
     * 创建隔离的票务写操作依赖并设置当前用户上下文。
     */
    @BeforeEach
    void setUp() {
        // 每个用例使用独立 Mock，避免操作状态在测试之间泄漏。
        ticketService = mock(TicketService.class);
        reliableCommandService = mock(ReliableCommandService.class);
        ticketOperationService = new TicketOperationService(
                ticketService,
                new BusinessOperationCoordinator(reliableCommandService));
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
        verify(reliableCommandService, never()).claim(any());
    }

    /**
     * 验证首次 Agent 取消成功后保存布尔终态。
     */
    @Test
    void claimsCancellationAndPersistsSuccess() {
        CancelTicketOrderReqDTO request = new CancelTicketOrderReqDTO(" action-1 ", "order-1");
        ReliableCommandRecord claimed = operation(
                "action-1", "CANCEL_TICKET_ORDER", "user-1", "fingerprint-1",
                ReliableCommandStatus.PROCESSING, null, "order-1", true);
        when(reliableCommandService.claim(any())).thenReturn(
                new ReliableCommandClaim(ReliableCommandClaim.Outcome.ACQUIRED, claimed));
        when(reliableCommandService.markSucceeded(any(), anyString(), anyString())).thenReturn(true);

        // 只有获得 operationId 执行权的请求可以进入真实取消链。
        ticketOperationService.cancelTicketOrder(request);

        verify(ticketService).cancelTicketOrder(request);
        verify(reliableCommandService).markSucceeded(claimed, "true", "order-1");
    }

    /**
     * 验证重复成功的取消请求不会再次释放订单和座位资源。
     */
    @Test
    void replaysSuccessfulCancellationWithoutSecondExecution() {
        CancelTicketOrderReqDTO request = new CancelTicketOrderReqDTO("action-1", "order-1");
        when(reliableCommandService.claim(any())).thenAnswer(invocation -> {
            ReliableCommandDefinition definition = invocation.getArgument(0);
            ReliableCommandRecord existing = operation(
                    "action-1", "CANCEL_TICKET_ORDER", "user-1", definition.requestFingerprint(),
                    ReliableCommandStatus.SUCCEEDED, "true", "order-1", false);
            return new ReliableCommandClaim(ReliableCommandClaim.Outcome.REPLAY_SUCCEEDED, existing);
        });

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
        ReliableCommandRecord claimed = operation(
                "action-1", "REFUND_TICKET", "user-1", "fingerprint-1",
                ReliableCommandStatus.PROCESSING, null, "order-1", true);
        when(reliableCommandService.claim(any())).thenReturn(
                new ReliableCommandClaim(ReliableCommandClaim.Outcome.ACQUIRED, claimed));
        when(reliableCommandService.markSucceeded(any(), anyString(), anyString())).thenReturn(true);
        when(ticketService.commonTicketRefund(request)).thenReturn(expected);

        // 票务操作认领前先规范化支付退款请求标识，确保整个调用链使用同一键。
        RefundTicketRespDTO actual = ticketOperationService.refundTicket(request);

        assertThat(actual).isSameAs(expected);
        assertThat(request.getRequestId()).isEqualTo("action-1");
        verify(reliableCommandService).markSucceeded(
                claimed, JSON.toJSONString(expected), "order-1");
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
        verify(reliableCommandService, never()).claim(any());
    }

    /**
     * 构造取消和退票场景使用的可靠命令记录。
     *
     * @param operationId 操作标识
     * @param operationType 操作类型
     * @param userId 所属用户
     * @param fingerprint 请求摘要
     * @param status 当前状态
     * @param resultPayload 可重放结果
     * @param businessReference 订单号等安全引用
     * @param leased 是否携带执行租约
     * @return 测试命令记录
     */
    private ReliableCommandRecord operation(
            String operationId,
            String operationType,
            String userId,
            String fingerprint,
            ReliableCommandStatus status,
            String resultPayload,
            String businessReference,
            boolean leased) {
        Instant now = Instant.EPOCH;
        ReliableCommandLease lease = leased
                ? new ReliableCommandLease("worker-1", now.plusSeconds(120), 1L)
                : null;
        // 只填充业务协调器进行重复判定和结果保存需要的字段。
        return new ReliableCommandRecord(
                BusinessOperationCoordinator.commandKey(operationId),
                operationType,
                ReliableCommandMode.REMOTE_EFFECT,
                userId,
                fingerprint,
                "ticket-v1",
                status,
                resultPayload,
                null,
                null,
                businessReference,
                lease == null ? null : lease.owner(),
                lease == null ? null : lease.until(),
                lease == null ? 1L : lease.fencingToken(),
                now,
                1,
                null,
                0,
                now,
                now);
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
