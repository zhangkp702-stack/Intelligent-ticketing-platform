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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketChainMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketPreviewRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.refund.TrainRefundTicketParamNotNullChainFilter;
import org.opengoofy.index12306.biz.ticketservice.service.impl.TicketServiceImpl;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainHandler;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证退票预览与真实退款共用的选票和金额计算边界。
 */
class TicketServiceImplTests {

    private TicketOrderRemoteService ticketOrderRemoteService;
    private TicketServiceImpl ticketService;

    /**
     * 创建仅包含退票预览所需依赖的票务服务。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // 预览流程只依赖订单远程服务和退款参数责任链，其余依赖不参与本用例。
        ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        PayRemoteService payRemoteService = mock(PayRemoteService.class);
        AbstractChainContext<RefundTicketReqDTO> refundChain = new AbstractChainContext<>();
        Map<String, List<AbstractChainHandler>> chainContainer =
                (Map<String, List<AbstractChainHandler>>) ReflectionTestUtils.getField(
                        refundChain, "abstractChainHandlerContainer");
        chainContainer.put(
                TicketChainMarkEnum.TRAIN_REFUND_TICKET_FILTER.name(),
                List.of(new TrainRefundTicketParamNotNullChainFilter()));
        ticketService = new TicketServiceImpl(
                null, null, null, null, ticketOrderRemoteService, payRemoteService, null, null, null, null,
                null, null, null, null, refundChain, null, null, null, null);
    }

    /**
     * 验证部分退票只汇总本次选中的已支付子订单，不使用整单乘客金额。
     */
    @Test
    void partialRefundPreviewOnlyCountsSelectedTicket() {
        TicketOrderPassengerDetailRespDTO selectedTicket = ticket("item-1", 1200);
        TicketOrderDetailRespDTO order = new TicketOrderDetailRespDTO();
        order.setOrderSn("order-1");
        order.setCanRefund(true);
        order.setPassengerDetails(List.of(selectedTicket, ticket("item-2", 5600)));
        when(ticketOrderRemoteService.querySelfTicketOrderByOrderSn("order-1"))
                .thenReturn(Results.success(order));
        when(ticketOrderRemoteService.queryTicketItemOrderById(any()))
                .thenReturn(Results.success(List.of(selectedTicket)));

        RefundTicketReqDTO request = new RefundTicketReqDTO();
        request.setOrderSn("order-1");
        request.setType(0);
        request.setSubOrderRecordIdReqList(List.of("item-1"));

        // 预览金额必须只等于被选中的一张车票金额。
        RefundTicketPreviewRespDTO result = ticketService.previewTicketRefund(request);
        assertThat(result.getRefundable()).isTrue();
        assertThat(result.getRefundAmount()).isEqualTo(1200);
        assertThat(result.getItems()).singleElement()
                .extracting("orderItemId", "refundableAmount")
                .containsExactly("item-1", 1200);
    }

    /**
     * 创建处于已支付状态的子订单明细。
     *
     * @param id 子订单记录标识
     * @param amount 车票金额
     * @return 可参与退款计算的子订单
     */
    private TicketOrderPassengerDetailRespDTO ticket(String id, int amount) {
        // 状态 10 与订单服务中的已支付子订单状态保持一致。
        return TicketOrderPassengerDetailRespDTO.builder()
                .id(id)
                .realName("测试乘客")
                .amount(amount)
                .status(10)
                .build();
    }
}
