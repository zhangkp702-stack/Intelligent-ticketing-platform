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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateResultMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 将订单创建结果 Outbox 可靠投递给票务服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketOrderCreateResultOutboxPublisher {

    private final ReliableEventStore reliableEventStore;
    private final RocketMQTemplate rocketMQTemplate;
    private final String workerId = "ticket-order-create-result-" + UUID.randomUUID();

    @Value("${index12306.order.create-result.batch-size:32}")
    private int batchSize;

    @Value("${index12306.order.create-result.lease-millis:30000}")
    private long leaseMillis;

    @Value("${index12306.order.create-result.retry-delay-millis:500}")
    private long retryDelayMillis;

    /**
     * 回收失效租约并投递有限批次的订单创建结果。
     */
    @Scheduled(fixedDelayString = "${index12306.order.create-result.interval-millis:200}")
    public void publishPendingResults() {
        try {
            Instant now = Instant.now();
            int normalizedBatchSize = Math.max(1, batchSize);
            // 进程在 MQ 确认前退出时，下一实例先回收其租约再继续同一事件的投递。
            reliableEventStore.recoverExpiredPublications(
                    TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE, now, normalizedBatchSize);
            List<ReliableOutboxRecord> events = reliableEventStore.claimPublishable(
                    TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE,
                    workerId,
                    now,
                    now.plusMillis(Math.max(1000L, leaseMillis)),
                    normalizedBatchSize);
            // 空批次不做任何 Broker 调用，避免定时扫描对正常建单链路造成额外压力。
            events.forEach(this::publishOne);
        } catch (RuntimeException exception) {
            // 单轮存储层异常由下一轮调度恢复，不能使定时任务永久停止。
            log.error("ticket_order_create_result_publish_cycle_failed exceptionType={}",
                    exception.getClass().getSimpleName(), exception);
        }
    }

    /**
     * 发送一条已取得租约的结果事件，并以 Outbox 围栏提交 Broker 确认。
     *
     * @param event 当前实例已经认领的结果 Outbox 事件
     */
    private void publishOne(ReliableOutboxRecord event) {
        try {
            // 载荷在订单事务内固定，投递重试不得重新查询或重建订单结果。
            TicketOrderCreateResultMessage message = JSON.parseObject(
                    event.payload(), TicketOrderCreateResultMessage.class);
            if (!isValidMessage(message, event)) {
                throw new IllegalStateException("订单创建结果 Outbox 载荷不完整");
            }
            SendResult sendResult = rocketMQTemplate.syncSend(
                    TicketOrderMessageConstants.CREATE_ORDER_TOPIC + ":"
                            + TicketOrderMessageConstants.CREATE_ORDER_RESULT_TAG,
                    message);
            if (sendResult == null || sendResult.getMsgId() == null || sendResult.getMsgId().isBlank()) {
                throw new IllegalStateException("RocketMQ 未返回订单创建结果消息标识");
            }
            // MQ 确认后的状态提交使用当前租约围栏，旧实例迟到时不能覆盖新实例的处理结果。
            boolean published = reliableEventStore.markPublished(
                    event.key(), requireLease(event), event.eventVersion(), sendResult.getMsgId(), Instant.now());
            if (!published) {
                log.warn("ticket_order_create_result_publish_fence_lost reservationId={}, attempt={}",
                        event.aggregateId(), event.publishAttemptCount());
                return;
            }
            log.info("ticket_order_create_result_published reservationId={}, status={}, attempt={}",
                    message.getReservationId(), message.getStatus(), event.publishAttemptCount());
        } catch (RuntimeException exception) {
            // 发送失败只回退 Outbox；若 Broker 已接收但确认前宕机，票务侧 Inbox 会消除重复消息。
            boolean rescheduled = reliableEventStore.markPublishFailed(
                    event.key(),
                    requireLease(event),
                    event.eventVersion(),
                    "ORDER_CREATE_RESULT_PUBLISH_RETRY",
                    safeMessage(exception),
                    Instant.now().plusMillis(Math.max(1L, retryDelayMillis)),
                    Instant.now());
            log.warn("ticket_order_create_result_publish_retry reservationId={}, attempt={}, rescheduled={}, exceptionType={}",
                    event.aggregateId(), event.publishAttemptCount(), rescheduled, exception.getClass().getSimpleName());
        }
    }

    /**
     * 校验 Outbox 载荷与事件主键仍属于同一订单创建结果。
     *
     * @param message 反序列化后的消息载荷
     * @param event 当前 Outbox 事件
     * @return 关联字段完整且协议版本一致时返回 true
     */
    private boolean isValidMessage(TicketOrderCreateResultMessage message, ReliableOutboxRecord event) {
        return message != null
                && message.getMessageVersion() != null
                && message.getMessageVersion() == event.eventVersion()
                && message.getMessageVersion() == TicketOrderMessageConstants.CREATE_ORDER_VERSION
                && hasText(message.getMessageId())
                && event.key().eventId().equals(message.getSourceMessageId())
                && event.aggregateId().equals(message.getReservationId())
                && hasText(message.getCommandId())
                && (TicketOrderMessageConstants.RESULT_SUCCEEDED.equals(message.getStatus())
                ? hasText(message.getOrderSn())
                : TicketOrderMessageConstants.RESULT_FAILED.equals(message.getStatus()));
    }

    /**
     * 获取已认领事件的完整发布租约。
     *
     * @param event 已取得当前实例租约的事件
     * @return 可提交围栏状态的发布租约
     */
    private ReliableEventLease requireLease(ReliableOutboxRecord event) {
        if (event.lease() == null) {
            throw new IllegalStateException("订单创建结果 Outbox 缺少发布租约");
        }
        return event.lease();
    }

    /**
     * 生成不包含订单载荷的限长异常摘要。
     *
     * @param throwable 当前投递异常
     * @return 最多 400 个字符的安全摘要
     */
    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "no-message";
        }
        return message.length() <= 400 ? message : message.substring(0, 400);
    }

    /**
     * 判断字符串是否包含非空白内容。
     *
     * @param value 待校验字符串
     * @return 存在有效字符时返回 true
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
