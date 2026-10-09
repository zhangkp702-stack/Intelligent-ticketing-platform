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

package org.opengoofy.index12306.biz.ticketservice.mq.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateResultMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.biz.ticketservice.service.impl.TicketSeatReservationReleaseService;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxStatus;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证订单创建结果消费者对成功绑定和明确失败释放的收敛行为。
 */
@ExtendWith(MockitoExtension.class)
class TicketOrderCreateResultConsumerTests {

    @Mock
    private TicketSeatReservationReleaseService reservationReleaseService;

    @Mock
    private ReliableEventStore reliableEventStore;

    @InjectMocks
    private TicketOrderCreateResultConsumer consumer;

    /**
     * 验证成功结果会绑定订单并完成外部 Inbox。
     */
    @Test
    void shouldBindOrderAndCompleteInboxForSucceededResult() {
        TicketOrderCreateResultMessage message = result(TicketOrderMessageConstants.RESULT_SUCCEEDED);
        when(reliableEventStore.claimExternalConsumption(
                any(ReliableEventKey.class), anyLong(), anyString(), anyString(), any(Instant.class),
                any(Instant.class), anyInt())).thenReturn(Optional.of(inbox(message)));
        when(reliableEventStore.completeConsumption(any(ReliableInboxKey.class), any(ReliableEventLease.class),
                any(Instant.class))).thenReturn(true);

        consumer.onMessage(message);

        verify(reservationReleaseService).bindOrder("reservation-1");
        verify(reservationReleaseService, never()).releasePreparedReservation(anyString());
        verify(reliableEventStore).completeConsumption(any(ReliableInboxKey.class), any(ReliableEventLease.class),
                any(Instant.class));
    }

    /**
     * 验证订单服务明确失败后只释放 PREPARED reservation。
     */
    @Test
    void shouldReleasePreparedReservationForFailedResult() {
        TicketOrderCreateResultMessage message = result(TicketOrderMessageConstants.RESULT_FAILED);
        when(reliableEventStore.claimExternalConsumption(
                any(ReliableEventKey.class), anyLong(), anyString(), anyString(), any(Instant.class),
                any(Instant.class), anyInt())).thenReturn(Optional.of(inbox(message)));
        when(reliableEventStore.completeConsumption(any(ReliableInboxKey.class), any(ReliableEventLease.class),
                any(Instant.class))).thenReturn(true);

        consumer.onMessage(message);

        verify(reservationReleaseService).releasePreparedReservation("reservation-1");
        verify(reservationReleaseService, never()).bindOrder(anyString());
    }

    /**
     * 创建订单服务已经持久化的结果消息样例。
     *
     * @param status 订单创建终态
     * @return 关联字段完整的成功或失败结果
     */
    private TicketOrderCreateResultMessage result(String status) {
        return TicketOrderCreateResultMessage.builder()
                .messageId("source-1:result")
                .messageVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .reservationId("reservation-1")
                .status(status)
                .failureCategory(TicketOrderMessageConstants.RESULT_FAILED.equals(status) ? "ServiceException" : null)
                .build();
    }

    /**
     * 创建已被当前消费者领取的 Inbox 样例。
     *
     * @param message 订单创建结果消息
     * @return 持有消费租约的 Inbox
     */
    private ReliableInboxRecord inbox(TicketOrderCreateResultMessage message) {
        ReliableEventKey eventKey = new ReliableEventKey(
                TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE, message.getMessageId());
        return new ReliableInboxRecord(
                new ReliableInboxKey(eventKey, "ticket-order-create-result-v2"),
                TicketOrderMessageConstants.CREATE_ORDER_VERSION,
                ReliableInboxStatus.PROCESSING,
                1,
                16,
                null,
                new ReliableEventLease("consumer", 1L),
                Instant.parse("2026-08-23T00:02:00Z"),
                null,
                null,
                Instant.parse("2026-08-23T00:00:00Z"),
                null,
                Instant.parse("2026-08-23T00:00:00Z"),
                Instant.parse("2026-08-23T00:00:00Z"));
    }
}
