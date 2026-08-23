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

package org.opengoofy.index12306.biz.orderservice.mq.produce;

import com.alibaba.fastjson2.JSON;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.orderservice.service.monitor.OrderCreateResultOutboxMetrics;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateResultMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证订单创建结果 Outbox 的并发派发、围栏提交和执行器拒绝边界。
 */
class TicketOrderCreateResultOutboxPublisherTests {

    /**
     * Broker 确认后必须以当前租约围栏完成结果 Outbox。
     */
    @Test
    void marksResultOutboxPublishedAfterBrokerAcknowledgement() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ReliableOutboxRecord event = event(1);
        when(eventStore.recoverExpiredPublications(anyString(), any(), anyInt())).thenReturn(0);
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(32)))
                .thenReturn(List.of(event));
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateResultMessage.class)))
                .thenReturn(sendResult("broker-result-1"));
        TicketOrderCreateResultOutboxPublisher publisher = publisher(eventStore, rocketMQTemplate, Runnable::run);

        // 同步执行器让用例直接验证 Broker 确认与围栏提交的关联。
        publisher.publishPendingResults();

        verify(rocketMQTemplate).syncSend(
                eq(TicketOrderMessageConstants.CREATE_ORDER_TOPIC + ":"
                        + TicketOrderMessageConstants.CREATE_ORDER_RESULT_TAG),
                any(TicketOrderCreateResultMessage.class));
        verify(eventStore).markPublished(eq(event.key()), eq(event.lease()), eq(1L),
                eq("broker-result-1"), any());
    }

    /**
     * Broker 发送异常时必须保留同一事件并按指数退避安排下一次投递。
     */
    @Test
    void reschedulesResultWhenBrokerSendFails() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ReliableOutboxRecord event = event(2);
        when(eventStore.recoverExpiredPublications(anyString(), any(), anyInt())).thenReturn(0);
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(32)))
                .thenReturn(List.of(event));
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateResultMessage.class)))
                .thenThrow(new IllegalStateException("broker timeout"));
        TicketOrderCreateResultOutboxPublisher publisher = publisher(eventStore, rocketMQTemplate, Runnable::run);

        publisher.publishPendingResults();

        verify(eventStore).markPublishFailed(eq(event.key()), eq(event.lease()), eq(1L),
                eq("ORDER_CREATE_RESULT_PUBLISH_RETRY"), anyString(), any(), any());
    }

    /**
     * Broker 已确认但本实例失去围栏时不能回退事件，后续实例可安全处理重复消息。
     */
    @Test
    void doesNotRetryWhenBrokerAcknowledgementLosesPublishFence() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ReliableOutboxRecord event = event(1);
        when(eventStore.recoverExpiredPublications(anyString(), any(), anyInt())).thenReturn(0);
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(32)))
                .thenReturn(List.of(event));
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateResultMessage.class)))
                .thenReturn(sendResult("broker-result-1"));
        when(eventStore.markPublished(any(), any(), anyLong(), anyString(), any())).thenReturn(false);
        TicketOrderCreateResultOutboxPublisher publisher = publisher(eventStore, rocketMQTemplate, Runnable::run);

        publisher.publishPendingResults();

        verify(eventStore).markPublished(eq(event.key()), eq(event.lease()), eq(1L),
                eq("broker-result-1"), any());
        verify(eventStore, never()).markPublishFailed(any(), any(), anyLong(), anyString(), anyString(), any(), any());
    }

    /**
     * 执行器饱和时尚未开始发送的事件必须立即退回 Outbox，不能等待租约到期。
     */
    @Test
    void reschedulesResultImmediatelyWhenExecutorRejects() {
        ReliableEventStore eventStore = mock(ReliableEventStore.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        ReliableOutboxRecord event = event(1);
        when(eventStore.recoverExpiredPublications(anyString(), any(), anyInt())).thenReturn(0);
        when(eventStore.claimPublishable(anyString(), anyString(), any(), any(), eq(32)))
                .thenReturn(List.of(event));
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("saturated");
        };
        TicketOrderCreateResultOutboxPublisher publisher = publisher(eventStore, rocketMQTemplate, rejectingExecutor);

        publisher.publishPendingResults();

        verify(eventStore).markPublishFailed(eq(event.key()), eq(event.lease()), eq(1L),
                eq("ORDER_CREATE_RESULT_EXECUTOR_REJECTED"), anyString(), any(), any());
    }

    /**
     * 创建带共享结果协议和发布租约的测试 Outbox 记录。
     *
     * @param attemptCount 当前认领次数
     * @return 可直接投递的结果事件
     */
    private ReliableOutboxRecord event(int attemptCount) {
        TicketOrderCreateResultMessage message = TicketOrderCreateResultMessage.builder()
                .messageId("source-message-a:result")
                .messageVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .sourceMessageId("source-message-a")
                .reservationId("reservation-a")
                .commandId("purchase-a:create-order")
                .status(TicketOrderMessageConstants.RESULT_SUCCEEDED)
                .orderSn("order-a")
                .build();
        Instant now = Instant.now();
        // eventId 与 sourceMessageId 保持一致，模拟订单事务已固定的结果 Outbox 主键。
        return new ReliableOutboxRecord(
                new ReliableEventKey(TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE, "source-message-a"),
                "source-message-a",
                TicketOrderMessageConstants.CREATE_ORDER_RESULT_EVENT_TYPE,
                "reservation-a",
                JSON.toJSONString(message),
                TicketOrderMessageConstants.CREATE_ORDER_VERSION,
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
     * 创建注入同步或拒绝执行器的结果 Outbox 发布器。
     *
     * @param eventStore 可靠事件存储
     * @param rocketMQTemplate RocketMQ 发送器
     * @param executor 测试使用的执行器
     * @return 固定批次和退避参数的发布器
     */
    private TicketOrderCreateResultOutboxPublisher publisher(
            ReliableEventStore eventStore,
            RocketMQTemplate rocketMQTemplate,
            Executor executor) {
        OrderCreateResultOutboxMetrics metrics = new OrderCreateResultOutboxMetrics(
                new SimpleMeterRegistry(), eventStore);
        TicketOrderCreateResultOutboxPublisher publisher = new TicketOrderCreateResultOutboxPublisher(
                eventStore, rocketMQTemplate, metrics, executor);
        // 固定调度参数，避免测试依赖 Spring 的配置注入。
        ReflectionTestUtils.setField(publisher, "batchSize", 32);
        ReflectionTestUtils.setField(publisher, "leaseMillis", 30000L);
        ReflectionTestUtils.setField(publisher, "retryBaseMillis", 500L);
        ReflectionTestUtils.setField(publisher, "retryMaxMillis", 30000L);
        return publisher;
    }

    /**
     * 创建包含 Broker 消息标识的成功发送结果。
     *
     * @param messageId RocketMQ 返回的消息标识
     * @return 可作为同步发送成功结果的对象
     */
    private SendResult sendResult(String messageId) {
        SendResult result = new SendResult();
        result.setMsgId(messageId);
        return result;
    }
}
