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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderItemCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.service.OrderService;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableConsumptionResult;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableInboxRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 消费票务服务发布的订单创建命令，并将命令交给订单服务的稳定 commandId 幂等逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = TicketOrderMessageConstants.CREATE_ORDER_TOPIC,
        selectorExpression = TicketOrderMessageConstants.CREATE_ORDER_TAG,
        consumerGroup = TicketOrderMessageConstants.CREATE_ORDER_CONSUMER_GROUP
)
public class TicketOrderCreateCommandConsumer implements RocketMQListener<TicketOrderCreateCommandMessage> {

    private static final String CONSUMER_NAME = "ticket-order-create-v1";
    private static final int MAX_CONSUME_ATTEMPTS = 16;
    private static final long LEASE_MILLIS = 120_000L;

    private final OrderService orderService;
    private final ReliableEventStore reliableEventStore;
    private final String consumerOwner = "ticket-order-create-" + UUID.randomUUID();

    /**
     * 领取外部订单创建消息，调用现有命令幂等建单逻辑，并在成功后提交 Inbox 围栏。
     *
     * @param message 票务服务持久化后发布的订单创建命令
     */
    @Override
    public void onMessage(TicketOrderCreateCommandMessage message) {
        if (!isValidMessage(message)) {
            // 无法构造稳定 Inbox 主键的坏消息只能确认消费，并保留告警日志供人工排查。
            log.error("ticket_order_create_message_invalid messageId={}",
                    message == null ? null : message.getMessageId());
            return;
        }
        Instant now = Instant.now();
        ReliableEventKey eventKey = new ReliableEventKey(
                TicketOrderMessageConstants.CREATE_ORDER_NAMESPACE, message.getMessageId());
        // 跨服务消息不共享生产者数据库，使用外部 Inbox 方法防止查询本地不存在的 Outbox。
        Optional<ReliableInboxRecord> claimed = reliableEventStore.claimExternalConsumption(
                eventKey, message.getMessageVersion(), CONSUMER_NAME, consumerOwner, now,
                now.plusMillis(LEASE_MILLIS), MAX_CONSUME_ATTEMPTS);
        if (claimed.isEmpty()) {
            // 同一消息正在被其他实例处理、已经完成或达到最大尝试次数时直接确认本次重复投递。
            return;
        }
        ReliableInboxRecord inbox = claimed.get();
        try {
            // 订单服务继续使用 commandId 防重，覆盖创建成功但 Inbox 提交前宕机的重放窗口。
            String orderSn = orderService.createTicketOrder(toOrderCreateRequest(message));
            if (orderSn == null || orderSn.isBlank()) {
                throw new IllegalStateException("订单服务未返回有效订单号");
            }
            // 仅持有当前消费围栏的实例可以完成 Inbox，迟到实例不会覆盖新的处理结果。
            if (!reliableEventStore.completeConsumption(inbox.key(), inbox.lease(), Instant.now())) {
                throw new IllegalStateException("订单创建 Inbox 消费围栏已失效");
            }
            log.info("ticket_order_create_consumed reservationId={}, commandId={}, orderSn={}, attempt={}",
                    message.getReservationId(), message.getCommandId(), orderSn, inbox.attemptCount());
        } catch (RuntimeException exception) {
            handleFailure(message, inbox, exception);
        }
    }

    /**
     * 校验消息能否组成稳定的协议版本、Inbox 主键和建单命令。
     *
     * @param message 待校验的订单创建命令
     * @return 必填协议字段完整时返回 true
     */
    private boolean isValidMessage(TicketOrderCreateCommandMessage message) {
        return message != null
                && message.getMessageVersion() != null
                && message.getMessageVersion() == TicketOrderMessageConstants.CREATE_ORDER_VERSION
                && hasText(message.getMessageId())
                && hasText(message.getReservationId())
                && hasText(message.getActionId())
                && hasText(message.getCommandId())
                && message.getRequest() != null
                && isNumericUserId(message.getRequest().getUserId())
                && message.getRequest().getTicketOrderItems() != null
                && !message.getRequest().getTicketOrderItems().isEmpty();
    }

    /**
     * 将共享消息快照转换为订单服务内部请求，避免订单领域 DTO 直接暴露到消息协议。
     *
     * @param message 已校验的订单创建命令
     * @return 可交给订单服务执行的内部请求
     */
    private TicketOrderCreateReqDTO toOrderCreateRequest(TicketOrderCreateCommandMessage message) {
        TicketOrderCreateRequest source = message.getRequest();
        TicketOrderCreateReqDTO target = new TicketOrderCreateReqDTO();
        // 命令标识始终以消息外层为准，防止载荷被错误复用时覆盖 Inbox 对应的稳定命令。
        target.setActionId(message.getActionId());
        target.setCommandId(message.getCommandId());
        target.setUserId(Long.parseLong(source.getUserId()));
        target.setUsername(source.getUsername());
        target.setTrainId(source.getTrainId());
        target.setDeparture(source.getDeparture());
        target.setArrival(source.getArrival());
        target.setSource(source.getSource());
        target.setOrderTime(source.getOrderTime());
        target.setRidingDate(source.getRidingDate());
        target.setTrainNumber(source.getTrainNumber());
        target.setDepartureTime(source.getDepartureTime());
        target.setArrivalTime(source.getArrivalTime());
        // 每张车票逐项复制，保留消息快照中的座位和乘车人身份信息。
        target.setTicketOrderItems(source.getTicketOrderItems().stream()
                .map(this::toOrderCreateItem)
                .toList());
        return target;
    }

    /**
     * 将共享车票快照转换为订单持久化使用的明细请求。
     *
     * @param source 共享消息中的车票快照
     * @return 订单服务内部车票明细
     */
    private TicketOrderItemCreateReqDTO toOrderCreateItem(TicketOrderCreateItem source) {
        TicketOrderItemCreateReqDTO target = new TicketOrderItemCreateReqDTO();
        // 订单明细只复制票务锁座阶段已经固定的字段，不再次查询乘车人或票价。
        target.setCarriageNumber(source.getCarriageNumber());
        target.setSeatType(source.getSeatType());
        target.setSeatNumber(source.getSeatNumber());
        target.setRealName(source.getRealName());
        target.setIdType(source.getIdType());
        target.setIdCard(source.getIdCard());
        target.setPhone(source.getPhone());
        target.setAmount(source.getAmount());
        target.setTicketType(source.getTicketType());
        return target;
    }

    /**
     * 将异常写入外部 Inbox，并在未耗尽次数时要求 RocketMQ 重投同一消息。
     *
     * @param message 当前订单创建命令
     * @param inbox 当前实例持有的 Inbox 租约
     * @param exception 本次建单异常
     */
    private void handleFailure(
            TicketOrderCreateCommandMessage message,
            ReliableInboxRecord inbox,
            RuntimeException exception) {
        Instant now = Instant.now();
        // 外部 Inbox 只更新本地状态；重新投递由随后抛出的 RocketMQ 消费异常触发。
        Optional<ReliableConsumptionResult> result = reliableEventStore.retryExternalConsumption(
                inbox.key(), inbox.lease(), exception.getClass().getSimpleName(), exception.getMessage(), now, now);
        if (result.isEmpty()) {
            log.warn("ticket_order_create_retry_fence_lost reservationId={}, commandId={}, exceptionType={}",
                    message.getReservationId(), message.getCommandId(), exception.getClass().getSimpleName());
            return;
        }
        if (!result.get().retryScheduled()) {
            log.error("ticket_order_create_retry_exhausted reservationId={}, commandId={}, attempt={}, exceptionType={}",
                    message.getReservationId(), message.getCommandId(), inbox.attemptCount(),
                    exception.getClass().getSimpleName());
            return;
        }
        throw exception;
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

    /**
     * 校验消息中的用户标识可以转换为订单分片使用的 Long 类型。
     *
     * @param userId 共享消息中的用户标识
     * @return 可安全转换为 Long 时返回 true
     */
    private boolean isNumericUserId(String userId) {
        if (!hasText(userId)) {
            return false;
        }
        try {
            // 订单服务按 Long 类型用户标识分片，提前校验避免无意义地进入 Broker 重试。
            Long.parseLong(userId);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
