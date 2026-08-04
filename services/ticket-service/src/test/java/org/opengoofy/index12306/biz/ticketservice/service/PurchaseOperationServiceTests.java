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
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
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
import java.util.Date;
import java.util.List;

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
    private ReliableCommandService reliableCommandService;
    private PurchaseOperationService purchaseOperationService;

    /**
     * 创建隔离的购票依赖并设置当前用户上下文。
     */
    @BeforeEach
    void setUp() {
        // 每个用例使用独立 Mock，避免操作认领状态在测试之间泄漏。
        ticketService = mock(TicketService.class);
        reliableCommandService = mock(ReliableCommandService.class);
        purchaseOperationService = new PurchaseOperationService(
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
        verify(reliableCommandService, never()).claim(any());
    }

    /**
     * 验证首次 Agent 操作成功认领后只执行一次真实购票并保存结果。
     */
    @Test
    void claimsOperationBeforePurchaseAndPersistsSuccess() {
        PurchaseTicketReqDTO request = request(" action-1 ");
        TicketPurchaseRespDTO expected = result("order-1");
        ReliableCommandRecord claimed = operation(
                "action-1", "PURCHASE_TICKET", "user-1", "fingerprint-1",
                ReliableCommandStatus.PROCESSING, null, true);
        when(reliableCommandService.claim(any())).thenReturn(
                new ReliableCommandClaim(ReliableCommandClaim.Outcome.ACQUIRED, claimed));
        when(reliableCommandService.markSucceeded(any(), anyString(), anyString())).thenReturn(true);
        when(ticketService.purchaseTicketsV2(request)).thenReturn(expected);

        // 操作标识会先规范化，再进入真实 V2 购票链。
        TicketPurchaseRespDTO actual = purchaseOperationService.purchaseTicketsV2(request);

        assertThat(actual).isSameAs(expected);
        verify(reliableCommandService).markSucceeded(
                claimed, JSON.toJSONString(expected), "order-1");
        verify(ticketService).purchaseTicketsV2(request);
    }

    /**
     * 验证重复成功请求直接返回持久化结果，不再次扣票。
     */
    @Test
    void replaysPersistedResultForDuplicateSuccessfulOperation() {
        PurchaseTicketReqDTO request = request("action-1");
        TicketPurchaseRespDTO expected = result("order-1");
        when(reliableCommandService.claim(any())).thenAnswer(invocation -> {
            ReliableCommandDefinition definition = invocation.getArgument(0);
            ReliableCommandRecord existing = operation(
                    "action-1", "PURCHASE_TICKET", "user-1", definition.requestFingerprint(),
                    ReliableCommandStatus.SUCCEEDED, JSON.toJSONString(expected), false);
            return new ReliableCommandClaim(ReliableCommandClaim.Outcome.REPLAY_SUCCEEDED, existing);
        });

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
        ReliableCommandRecord existing = operation(
                "action-1", "PURCHASE_TICKET", "user-1", "different-fingerprint",
                ReliableCommandStatus.SUCCEEDED, JSON.toJSONString(result("order-1")), false);
        when(reliableCommandService.claim(any())).thenReturn(
                new ReliableCommandClaim(ReliableCommandClaim.Outcome.PAYLOAD_MISMATCH, existing));

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
        ReliableCommandRecord claimed = operation(
                "action-1", "PURCHASE_TICKET", "user-1", "fingerprint-1",
                ReliableCommandStatus.PROCESSING, null, true);
        when(reliableCommandService.claim(any())).thenReturn(
                new ReliableCommandClaim(ReliableCommandClaim.Outcome.ACQUIRED, claimed));
        when(ticketService.purchaseTicketsV2(request)).thenThrow(failure);

        // 相同操作标识后续不能再次进入扣票链，调用方需要创建新的操作。
        assertThatThrownBy(() -> purchaseOperationService.purchaseTicketsV2(request))
                .isSameAs(failure);
        verify(reliableCommandService).markUnknown(
                org.mockito.ArgumentMatchers.eq(claimed),
                org.mockito.ArgumentMatchers.eq("DOWNSTREAM_RESULT_UNKNOWN"),
                org.mockito.ArgumentMatchers.eq("列车站点已无余票"),
                any(Instant.class));
        verify(reliableCommandService, never()).markSucceeded(any(), anyString(), anyString());
    }

    /**
     * 构造 Ticket 可靠命令测试记录。
     *
     * @param operationId 操作标识
     * @param operationType 操作类型
     * @param userId 所属用户
     * @param fingerprint 请求摘要
     * @param status 当前状态
     * @param resultPayload 可重放结果
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
            boolean leased) {
        Instant now = Instant.EPOCH;
        ReliableCommandLease lease = leased
                ? new ReliableCommandLease("worker-1", now.plusSeconds(120), 1L)
                : null;
        // 只为当前用例填充命令认领和结果重放所需字段。
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
                null,
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
