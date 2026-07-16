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

package org.opengoofy.index12306.biz.payservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.payservice.dao.entity.RefundDO;
import org.opengoofy.index12306.biz.payservice.dao.mapper.PayMapper;
import org.opengoofy.index12306.biz.payservice.dao.mapper.RefundMapper;
import org.opengoofy.index12306.biz.payservice.dto.RefundReqDTO;
import org.opengoofy.index12306.biz.payservice.dto.RefundRespDTO;
import org.opengoofy.index12306.biz.payservice.mq.produce.RefundResultCallbackOrderSendProduce;
import org.opengoofy.index12306.biz.payservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.payservice.service.impl.RefundServiceImpl;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证支付退款请求的持久化幂等语义。
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceImplTests {

    @Mock
    private PayMapper payMapper;

    @Mock
    private RefundMapper refundMapper;

    @Mock
    private TicketOrderRemoteService ticketOrderRemoteService;

    @Mock
    private AbstractStrategyChoose abstractStrategyChoose;

    @Mock
    private RefundResultCallbackOrderSendProduce refundResultCallbackOrderSendProduce;

    @InjectMocks
    private RefundServiceImpl refundService;

    /**
     * 验证相同退款请求已经成功时直接返回原结果，不再次调用第三方渠道。
     */
    @Test
    void repeatedRefundRequestReturnsStoredResult() {
        RefundDO first = new RefundDO();
        first.setRefundRequestId("refund-request-1");
        first.setOrderSn("order-1");
        first.setAmount(1200);
        first.setStatus(10);
        first.setTradeNo("trade-1");
        RefundDO second = new RefundDO();
        second.setRefundRequestId("refund-request-1");
        second.setOrderSn("order-1");
        second.setAmount(800);
        second.setStatus(10);
        second.setTradeNo("trade-1");
        when(refundMapper.selectList(any())).thenReturn(List.of(first, second));

        // 命中持久化请求标识后只聚合已有明细，不查询支付单也不调用退款渠道。
        RefundReqDTO request = new RefundReqDTO();
        request.setRequestId("refund-request-1");
        request.setOrderSn("order-1");
        RefundRespDTO result = refundService.commonRefund(request);
        assertThat(result.getRequestId()).isEqualTo("refund-request-1");
        assertThat(result.getRefundAmount()).isEqualTo(2000);
        assertThat(result.getStatus()).isEqualTo(10);
        assertThat(result.getTradeNo()).isEqualTo("trade-1");
        verifyNoInteractions(payMapper, abstractStrategyChoose, refundResultCallbackOrderSendProduce);
    }
}
