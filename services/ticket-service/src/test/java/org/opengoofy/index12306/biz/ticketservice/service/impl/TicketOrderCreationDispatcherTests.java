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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import com.alibaba.fastjson2.JSON;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderItemCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketOrderCreationMetrics;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证异步建单 MQ 派发的确认、历史载荷转换和失败重试边界。
 */
class TicketOrderCreationDispatcherTests {

    /**
     * MQ 确认后必须使用租约围栏完成票务 Outbox，不等待订单创建或座位绑定。
     */
    @Test
    void marksOutboxPublishedAfterCommandBrokerAcknowledgement() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ReliableOutboxRecord event = event(1);
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(100)))
                .thenReturn(List.of(event));
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateCommandMessage.class)))
                .thenReturn(sendResult("broker-1"));
        TicketOrderCreationDispatcher dispatcher = dispatcher(eventStore, rocketMQTemplate);

        // 一次扫描在当前线程同步执行，便于验证 Broker 确认与 Outbox 围栏提交顺序。
        dispatcher.dispatchPendingOrders();

        verify(rocketMQTemplate).syncSend(
                eq(TicketOrderMessageConstants.CREATE_ORDER_TOPIC + ":"
                        + TicketOrderMessageConstants.CREATE_ORDER_TAG),
                any(TicketOrderCreateCommandMessage.class));
        verify(eventStore).markPublished(eq(event.key()), eq(event.lease()), eq(1L), eq("broker-1"), any());
    }

    /**
     * Broker 发送异常时只安排同一事件重试，不推断订单创建结果或释放座位。
     */
    @Test
    void reschedulesCommandWhenBrokerSendFails() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ReliableOutboxRecord event = event(2);
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(100)))
                .thenReturn(List.of(event));
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateCommandMessage.class)))
                .thenThrow(new IllegalStateException("timeout"));
        TicketOrderCreationDispatcher dispatcher = dispatcher(eventStore, rocketMQTemplate);

        // Broker 超时无法证明消息未提交，必须保留并重放稳定 commandId 和 messageId。
        dispatcher.dispatchPendingOrders();

        verify(eventStore).markPublishFailed(eq(event.key()), eq(event.lease()), eq(1L),
                eq("ORDER_CREATE_COMMAND_PUBLISH_RETRY"), anyString(), any(), any());
    }

    /**
     * 执行器饱和时必须立即退回当前事件，不能等发布租约自然超时后才恢复。
     */
    @Test
    void reschedulesEventImmediatelyWhenExecutorRejects() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ReliableOutboxRecord event = event(1);
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(100)))
                .thenReturn(List.of(event));
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("saturated");
        };
        TicketOrderCreationDispatcher dispatcher = dispatcher(
                eventStore, rocketMQTemplate, rejectingExecutor);

        // 拒绝发生在任务开始前，必须以现有围栏立即安排重试。
        dispatcher.dispatchPendingOrders();

        verify(eventStore).markPublishFailed(eq(event.key()), eq(event.lease()), eq(1L),
                eq("ORDER_CREATE_EXECUTOR_REJECTED"), anyString(), any(), any());
    }

    /**
     * 验证已持久化的内部 DTO 不走 Feign，而是转换为共享命令后发送到 RocketMQ。
     */
    @Test
    void convertsLegacyPayloadToSharedCommandBeforePublishing() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ReliableOutboxRecord event = legacyEvent();
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(100)))
                .thenReturn(List.of(event));
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateCommandMessage.class)))
                .thenReturn(sendResult("broker-legacy"));
        TicketOrderCreationDispatcher dispatcher = dispatcher(eventStore, rocketMQTemplate);

        dispatcher.dispatchPendingOrders();

        ArgumentCaptor<TicketOrderCreateCommandMessage> captor =
                ArgumentCaptor.forClass(TicketOrderCreateCommandMessage.class);
        verify(rocketMQTemplate).syncSend(anyString(), captor.capture());
        TicketOrderCreateCommandMessage message = captor.getValue();
        assertThat(message.getMessageId()).isEqualTo("reservation-a");
        assertThat(message.getReservationId()).isEqualTo("reservation-a");
        assertThat(message.getCommandId()).isEqualTo("purchase-a:create-order");
        assertThat(message.getRequest().getTicketOrderItems()).singleElement().satisfies(item ->
                assertThat(item.getSeatNumber()).isEqualTo("01A"));
    }

    /**
     * 创建使用同步执行器的派发器。
     *
     * @param eventStore 可靠事件存储
     * @param rocketMQTemplate RocketMQ 命令发送器
     * @return 已注入测试参数的派发器
     */
    private TicketOrderCreationDispatcher dispatcher(
            ReliableEventStore eventStore,
            RocketMQTemplate rocketMQTemplate) {
        return dispatcher(eventStore, rocketMQTemplate, Runnable::run);
    }

    /**
     * 创建可替换执行器的派发器。
     *
     * @param eventStore 可靠事件存储
     * @param rocketMQTemplate RocketMQ 命令发送器
     * @param executor 用于模拟同步执行或饱和拒绝的执行器
     * @return 已注入测试参数的派发器
     */
    private TicketOrderCreationDispatcher dispatcher(
            ReliableEventStore eventStore,
            RocketMQTemplate rocketMQTemplate,
            Executor executor) {
        TicketOrderCreationMetrics metrics = new TicketOrderCreationMetrics(
                new SimpleMeterRegistry(), eventStore);
        TicketOrderCreationDispatcher dispatcher = new TicketOrderCreationDispatcher(
                eventStore, rocketMQTemplate, metrics, executor);
        // 固定批次、租约和退避参数，避免单元测试依赖 Spring 配置注入。
        ReflectionTestUtils.setField(dispatcher, "batchSize", 100);
        ReflectionTestUtils.setField(dispatcher, "leaseMillis", 30000L);
        ReflectionTestUtils.setField(dispatcher, "retryBaseMillis", 200L);
        ReflectionTestUtils.setField(dispatcher, "retryMaxMillis", 5000L);
        return dispatcher;
    }

    /**
     * 创建已取得有效租约的建单 Outbox 记录。
     *
     * @param attemptCount 当前认领次数
     * @return 可直接派发的测试事件
     */
    private ReliableOutboxRecord event(int attemptCount) {
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
        TicketOrderCreateRequest request = TicketOrderCreateRequest.builder()
                .userId("1001")
                .username("alice")
                .ticketOrderItems(List.of(item))
                .build();
        TicketOrderCreateCommandMessage message = TicketOrderCreateCommandMessage.builder()
                .messageId("reservation-a")
                .messageVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .reservationId("reservation-a")
                .actionId("purchase-a")
                .commandId("purchase-a:create-order")
                .request(request)
                .build();
        Instant now = Instant.now();
        // 事件携带 worker 和围栏令牌，用于验证迟到实例无法覆盖新结果。
        return new ReliableOutboxRecord(
                new ReliableEventKey("ticket-order-creation", "reservation-a"),
                "purchase-a:create-order",
                "CREATE_TICKET_ORDER",
                "reservation-a",
                JSON.toJSONString(message),
                1L,
                ReliableOutboxStatus.PUBLISHING,
                now,
                new ReliableEventLease("worker-a", 3L),
                now.plusSeconds(30),
                attemptCount,
                null,
                null,
                null,
                null,
                now,
                now);
    }

    /**
     * 创建使用历史内部 DTO 载荷的未发送 Outbox 记录。
     *
     * @return 可转换为当前共享命令的历史事件
     */
    private ReliableOutboxRecord legacyEvent() {
        TicketOrderCreateRemoteReqDTO request = TicketOrderCreateRemoteReqDTO.builder()
                .actionId("purchase-a")
                .commandId("purchase-a:create-order")
                .userId("1001")
                .username("alice")
                .ticketOrderItems(List.of(TicketOrderItemCreateRemoteReqDTO.builder()
                        .carriageNumber("01")
                        .seatType(1)
                        .seatNumber("01A")
                        .realName("alice")
                        .idType(1)
                        .idCard("id-card")
                        .phone("13800000000")
                        .amount(100)
                        .ticketType(0)
                        .build()))
                .build();
        Instant now = Instant.now();
        return new ReliableOutboxRecord(
                new ReliableEventKey("ticket-order-creation", "reservation-a"),
                "purchase-a:create-order",
                "CREATE_TICKET_ORDER",
                "reservation-a",
                JSON.toJSONString(request),
                1L,
                ReliableOutboxStatus.PUBLISHING,
                now,
                new ReliableEventLease("worker-a", 3L),
                now.plusSeconds(30),
                1,
                null,
                null,
                null,
                null,
                now,
                now);
    }

    /**
     * 创建包含 Broker 消息标识的发送确认。
     *
     * @param messageId Broker 返回的消息标识
     * @return 可作为 RocketMQ 成功返回值的结果
     */
    private SendResult sendResult(String messageId) {
        SendResult result = new SendResult();
        result.setMsgId(messageId);
        return result;
    }
}
