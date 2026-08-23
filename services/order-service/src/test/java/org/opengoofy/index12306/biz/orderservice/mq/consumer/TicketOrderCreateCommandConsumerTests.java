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

package org.opengoofy.index12306.biz.orderservice.mq.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.service.OrderService;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateResultMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证订单创建 MQ 消费者的消息领取、请求映射和 Inbox 完成行为。
 */
@ExtendWith(MockitoExtension.class)
class TicketOrderCreateCommandConsumerTests {

    @Mock
    private OrderService orderService;

    @Mock
    private ReliableEventStore reliableEventStore;

    @InjectMocks
    private TicketOrderCreateCommandConsumer consumer;

    /**
     * 验证有效命令会映射为内部请求并在订单创建成功后完成 Inbox。
     */
    @Test
    void shouldCreateOrderAndCompleteInboxForClaimedMessage() {
        TicketOrderCreateCommandMessage message = message();
        ReliableInboxRecord inbox = inbox(message.getMessageId());
        when(reliableEventStore.claimExternalConsumption(
                any(ReliableEventKey.class), anyLong(), anyString(), anyString(), any(Instant.class),
                any(Instant.class), anyInt())).thenReturn(Optional.of(inbox));
        when(orderService.createTicketOrderFromMessage(
                any(TicketOrderCreateReqDTO.class), anyString(), anyString()))
                .thenReturn(successResult(message));
        when(reliableEventStore.completeConsumption(any(ReliableInboxKey.class), any(ReliableEventLease.class),
                any(Instant.class))).thenReturn(true);

        consumer.onMessage(message);

        ArgumentCaptor<TicketOrderCreateReqDTO> requestCaptor = ArgumentCaptor.forClass(TicketOrderCreateReqDTO.class);
        verify(orderService).createTicketOrderFromMessage(
                requestCaptor.capture(), eq(message.getMessageId()), eq(message.getReservationId()));
        TicketOrderCreateReqDTO request = requestCaptor.getValue();
        assertThat(request.getActionId()).isEqualTo(message.getActionId());
        assertThat(request.getCommandId()).isEqualTo(message.getCommandId());
        assertThat(request.getUserId()).isEqualTo(10001L);
        assertThat(request.getTicketOrderItems()).singleElement().satisfies(item -> {
            assertThat(item.getCarriageNumber()).isEqualTo("01");
            assertThat(item.getSeatNumber()).isEqualTo("01A");
            assertThat(item.getIdCard()).isEqualTo("id-card");
        });
        verify(reliableEventStore).completeConsumption(any(ReliableInboxKey.class), any(ReliableEventLease.class),
                any(Instant.class));
    }

    /**
     * 验证被其他实例领取的重复消息不会再次执行建单。
     */
    @Test
    void shouldSkipDuplicateMessageWhenInboxIsNotClaimed() {
        when(reliableEventStore.claimExternalConsumption(
                any(ReliableEventKey.class), anyLong(), anyString(), anyString(), any(Instant.class),
                any(Instant.class), anyInt())).thenReturn(Optional.empty());

        consumer.onMessage(message());

        verify(orderService, never()).createTicketOrderFromMessage(
                any(TicketOrderCreateReqDTO.class), anyString(), anyString());
        verify(reliableEventStore, never()).completeConsumption(
                any(ReliableInboxKey.class), any(ReliableEventLease.class), any(Instant.class));
    }

    /**
     * 创建完整的共享订单命令样例。
     *
     * @return 可被消费者接受的订单创建命令
     */
    private TicketOrderCreateCommandMessage message() {
        TicketOrderCreateItem item = TicketOrderCreateItem.builder()
                .carriageNumber("01")
                .seatType(1)
                .seatNumber("01A")
                .realName("test")
                .idType(1)
                .idCard("id-card")
                .phone("13800000000")
                .amount(100)
                .ticketType(0)
                .build();
        TicketOrderCreateRequest request = TicketOrderCreateRequest.builder()
                .userId("10001")
                .username("test")
                .trainId(1L)
                .departure("A")
                .arrival("B")
                .ticketOrderItems(List.of(item))
                .build();
        return TicketOrderCreateCommandMessage.builder()
                .messageId("reservation-1")
                .messageVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .reservationId("reservation-1")
                .actionId("action-1")
                .commandId("action-1:create-order")
                .request(request)
                .build();
    }

    /**
     * 创建订单服务已经持久化的成功结果样例。
     *
     * @param message 原始订单创建命令
     * @return 关联字段完整的成功结果
     */
    private TicketOrderCreateResultMessage successResult(TicketOrderCreateCommandMessage message) {
        return TicketOrderCreateResultMessage.builder()
                .messageId(message.getMessageId() + ":result")
                .messageVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .sourceMessageId(message.getMessageId())
                .reservationId(message.getReservationId())
                .commandId(message.getCommandId())
                .status(TicketOrderMessageConstants.RESULT_SUCCEEDED)
                .orderSn("O123")
                .build();
    }

    /**
     * 创建被当前消费者领取的 Inbox 样例。
     *
     * @param eventId 外部消息标识
     * @return 持有消费租约的 Inbox
     */
    private ReliableInboxRecord inbox(String eventId) {
        ReliableEventKey eventKey = new ReliableEventKey(
                TicketOrderMessageConstants.CREATE_ORDER_NAMESPACE, eventId);
        return new ReliableInboxRecord(
                new ReliableInboxKey(eventKey, "ticket-order-create-v1"),
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
