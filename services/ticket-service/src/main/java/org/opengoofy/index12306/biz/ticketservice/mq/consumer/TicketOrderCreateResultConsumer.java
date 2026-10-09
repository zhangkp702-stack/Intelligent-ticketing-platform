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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateResultMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.biz.ticketservice.service.impl.TicketSeatReservationReleaseService;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableConsumptionResult;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 消费订单服务回传的建单终态，并收敛票务预留座位状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = TicketOrderMessageConstants.CREATE_ORDER_TOPIC,
        selectorExpression = TicketOrderMessageConstants.CREATE_ORDER_RESULT_TAG,
        consumerGroup = TicketOrderMessageConstants.CREATE_ORDER_RESULT_CONSUMER_GROUP
)
public class TicketOrderCreateResultConsumer implements RocketMQListener<TicketOrderCreateResultMessage> {

    private static final String CONSUMER_NAME = "ticket-order-create-result-v2";
    private static final int MAX_CONSUME_ATTEMPTS = 16;
    private static final long LEASE_MILLIS = 120_000L;

    private final TicketSeatReservationReleaseService reservationReleaseService;
    private final ReliableEventStore reliableEventStore;
    private final String consumerOwner = "ticket-order-create-result-" + UUID.randomUUID();

    /**
     * 领取订单创建结果，成功时绑定订单，明确失败时释放 PREPARED 座位。
     *
     * @param message 订单服务本地事务写入并发布的终态结果
     */
    @Override
    public void onMessage(TicketOrderCreateResultMessage message) {
        if (!isValidMessage(message)) {
            // 协议字段缺失时无法安全构造 Inbox 主键，只能确认并保留告警供人工处理。
            log.error("ticket_order_create_result_invalid messageId={}",
                    message == null ? null : message.getMessageId());
            return;
        }
        Instant now = Instant.now();
        ReliableEventKey eventKey = new ReliableEventKey(
                TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE, message.getMessageId());
        // 结果事件来自订单库，票务库只使用外部 Inbox 完成幂等、租约和失败次数控制。
        Optional<ReliableInboxRecord> claimed = reliableEventStore.claimExternalConsumption(
                eventKey, message.getMessageVersion(), CONSUMER_NAME, consumerOwner, now,
                now.plusMillis(LEASE_MILLIS), MAX_CONSUME_ATTEMPTS);
        if (claimed.isEmpty()) {
            // 已完成、正在处理或达到上限的重复 Broker 投递无需再次修改 reservation。
            return;
        }
        ReliableInboxRecord inbox = claimed.get();
        try {
            applyResult(message);
            // 先完成数据库中的 reservation 状态迁移，再以当前围栏提交 Inbox。
            if (!reliableEventStore.completeConsumption(inbox.key(), inbox.lease(), Instant.now())) {
                throw new IllegalStateException("订单创建结果 Inbox 消费围栏已失效");
            }
            log.info("ticket_order_create_result_consumed reservationId={}, status={}, attempt={}",
                    message.getReservationId(), message.getStatus(), inbox.attemptCount());
        } catch (RuntimeException exception) {
            handleFailure(message, inbox, exception);
        }
    }

    /**
     * 根据订单服务已确定的终态推进 reservation，禁止将未知异常解释为失败释放。
     *
     * @param message 已领取的订单创建结果
     */
    private void applyResult(TicketOrderCreateResultMessage message) {
        if (TicketOrderMessageConstants.RESULT_SUCCEEDED.equals(message.getStatus())) {
            // reservationId 已是订单号，bindOrder 只需推进预订状态，可覆盖 Inbox 未完成的重复投递窗口。
            reservationReleaseService.bindOrder(message.getReservationId());
            return;
        }
        // 只有订单服务显式 FAILED 的结果才允许释放，PROCESSING 或协议异常绝不归还库存。
        reservationReleaseService.releasePreparedReservation(message.getReservationId());
    }

    /**
     * 校验结果消息能否安全驱动预留座位的最终状态迁移。
     *
     * @param message 待校验的订单创建结果
     * @return 版本、关联字段和成功失败语义完整时返回 true
     */
    private boolean isValidMessage(TicketOrderCreateResultMessage message) {
        if (message == null
                || message.getMessageVersion() == null
                || message.getMessageVersion() != TicketOrderMessageConstants.CREATE_ORDER_VERSION
                || !hasText(message.getMessageId())
                || !hasText(message.getReservationId())) {
            return false;
        }
        // 成功订单号由 reservationId 确定性推导；失败结果只根据稳定 FAILED 状态执行释放。
        return TicketOrderMessageConstants.RESULT_SUCCEEDED.equals(message.getStatus())
                || TicketOrderMessageConstants.RESULT_FAILED.equals(message.getStatus());
    }

    /**
     * 持久化当前处理异常，并在允许重试时通知 RocketMQ 重投同一结果消息。
     *
     * @param message 当前订单创建结果
     * @param inbox 当前实例持有的 Inbox 租约
     * @param exception reservation 收敛或 Inbox 提交异常
     */
    private void handleFailure(
            TicketOrderCreateResultMessage message,
            ReliableInboxRecord inbox,
            RuntimeException exception) {
        Instant now = Instant.now();
        // 外部消息只能更新本地 Inbox，重新投递由抛出消费异常交给 RocketMQ 处理。
        Optional<ReliableConsumptionResult> result = reliableEventStore.retryExternalConsumption(
                inbox.key(), inbox.lease(), exception.getClass().getSimpleName(), safeMessage(exception), now, now);
        if (result.isEmpty()) {
            log.warn("ticket_order_create_result_retry_fence_lost reservationId={}, exceptionType={}",
                    message.getReservationId(), exception.getClass().getSimpleName());
            return;
        }
        if (!result.get().retryScheduled()) {
            log.error("ticket_order_create_result_retry_exhausted reservationId={}, attempt={}, exceptionType={}",
                    message.getReservationId(), inbox.attemptCount(),
                    exception.getClass().getSimpleName());
            return;
        }
        throw exception;
    }

    /**
     * 生成不包含订单载荷的限长异常摘要。
     *
     * @param throwable 当前处理异常
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
