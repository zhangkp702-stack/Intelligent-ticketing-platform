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

package org.opengoofy.index12306.biz.canalrelay.relay;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.Message;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.canalrelay.config.CanalRelayProperties;
import org.opengoofy.index12306.biz.canalrelay.relay.CanalOutboxMessageRouter.RelayCommand;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 RocketMQ 确认与 Canal ACK/回滚的批次顺序。
 */
class CanalOutboxRelayTests {

    /**
     * 只有 RocketMQ 返回确认后才允许 ACK 当前 Canal 批次。
     */
    @Test
    void acknowledgesBatchAfterAllCommandsArePublished() {
        CanalOutboxMessageRouter router = mock(CanalOutboxMessageRouter.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        CanalConnector connector = mock(CanalConnector.class);
        RelayCommand command = command();
        when(router.route(any())).thenReturn(List.of(command));
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateCommandMessage.class)))
                .thenReturn(sendResult("broker-1"));
        CanalOutboxRelay relay = relay(router, rocketMQTemplate);

        relay.processBatch(connector, message(7L));

        var ordered = inOrder(rocketMQTemplate, connector);
        ordered.verify(rocketMQTemplate).syncSend(anyString(), any(TicketOrderCreateCommandMessage.class));
        ordered.verify(connector).ack(7L);
        verify(connector, never()).rollback(7L);
    }

    /**
     * Broker 发送异常或确认不确定时必须回滚，而不能 ACK 已部分投递的批次。
     */
    @Test
    void rollsBackBatchWhenBrokerSendFails() {
        CanalOutboxMessageRouter router = mock(CanalOutboxMessageRouter.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        CanalConnector connector = mock(CanalConnector.class);
        when(router.route(any())).thenReturn(List.of(command()));
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateCommandMessage.class)))
                .thenThrow(new IllegalStateException("timeout"));
        CanalOutboxRelay relay = relay(router, rocketMQTemplate);

        assertThatThrownBy(() -> relay.processBatch(connector, message(8L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RocketMQ 未确认完整 Canal 批次");

        verify(connector).rollback(8L);
        verify(connector, never()).ack(8L);
    }

    /**
     * 创建使用同步执行器的 Relay，使单元测试直接验证 ACK 先后顺序。
     *
     * @param router Outbox 行路由器
     * @param rocketMQTemplate RocketMQ 发送器
     * @return 不连接真实 Canal 的 Relay 实例
     */
    private CanalOutboxRelay relay(CanalOutboxMessageRouter router, RocketMQTemplate rocketMQTemplate) {
        return new CanalOutboxRelay(
                new CanalRelayProperties(),
                properties -> mock(CanalConnector.class),
                router,
                rocketMQTemplate,
                Runnable::run);
    }

    /**
     * 构造一条可发送的 Relay 命令。
     *
     * @return 带完整共享载荷的稳定命令
     */
    private RelayCommand command() {
        TicketOrderCreateItem item = TicketOrderCreateItem.builder()
                .carriageNumber("01")
                .seatType(1)
                .seatNumber("01A")
                .realName("alice")
                .idType(1)
                .idCard("id-card")
                .phone("13800000000")
                .amount(100)
                .ticketType(0)
                .build();
        TicketOrderCreateCommandMessage message = TicketOrderCreateCommandMessage.builder()
                .messageId("reservation-a")
                .messageVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .reservationId("reservation-a")
                .actionId("purchase-a")
                .commandId("purchase-a:create-order")
                .request(TicketOrderCreateRequest.builder().userId("1001").ticketOrderItems(List.of(item)).build())
                .build();
        return new RelayCommand(
                "reservation-a",
                "reservation-a",
                "purchase-a:create-order",
                TicketOrderMessageConstants.CREATE_ORDER_TOPIC,
                TicketOrderMessageConstants.CREATE_ORDER_TAG,
                0,
                message);
    }

    /**
     * 创建只携带批次标识的模拟 Canal 消息。
     *
     * @param batchId Canal 批次标识
     * @return 不需要真实 Binlog 条目的测试消息
     */
    private Message message(long batchId) {
        Message message = mock(Message.class);
        when(message.getId()).thenReturn(batchId);
        return message;
    }

    /**
     * 创建 RocketMQ 成功确认结果。
     *
     * @param messageId Broker 返回的消息标识
     * @return 可视为已确认的发送结果
     */
    private SendResult sendResult(String messageId) {
        SendResult result = new SendResult();
        result.setMsgId(messageId);
        return result;
    }
}
