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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCommandStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.service.OrderCommandExecutionService;
import org.opengoofy.index12306.biz.orderservice.service.OrderCommandPersistenceService;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证订单创建 MQ 消费者只负责可靠持久化 Command 和即时唤醒执行器。
 */
@ExtendWith(MockitoExtension.class)
class TicketOrderCreateCommandConsumerTests {

    @Mock
    private OrderCommandPersistenceService orderCommandPersistenceService;

    @Mock
    private OrderCommandExecutionService orderCommandExecutionService;

    @InjectMocks
    private TicketOrderCreateCommandConsumer consumer;

    /**
     * 验证 Command 本地事务成功后才会唤醒执行器，消费者不再登记通用 Inbox。
     */
    @Test
    void shouldPersistCommandBeforeDispatchingExecution() {
        TicketOrderCreateCommandMessage message = message();
        OrderCommandDO command = OrderCommandDO.builder()
                .userId("10001")
                .reservationId(message.getReservationId())
                .status(OrderCommandStatusEnum.RECEIVED.name())
                .build();
        when(orderCommandPersistenceService.persistReceivedCommand(message)).thenReturn(command);

        consumer.onMessage(message);

        verify(orderCommandPersistenceService).persistReceivedCommand(message);
        verify(orderCommandExecutionService).dispatchAsync("10001", message.getReservationId());
    }

    /**
     * 验证 Command 持久化失败时向 RocketMQ 抛出异常，并且不能启动内存任务后误确认消息。
     */
    @Test
    void shouldNotDispatchWhenCommandPersistenceFails() {
        TicketOrderCreateCommandMessage message = message();
        when(orderCommandPersistenceService.persistReceivedCommand(message))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        verify(orderCommandExecutionService, never()).dispatchAsync("10001", message.getReservationId());
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
                .messageVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .reservationId("reservation-1")
                .request(request)
                .build();
    }

}
