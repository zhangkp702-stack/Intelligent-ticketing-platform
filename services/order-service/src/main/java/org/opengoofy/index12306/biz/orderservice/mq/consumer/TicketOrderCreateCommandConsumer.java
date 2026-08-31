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
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.service.OrderCommandExecutionService;
import org.opengoofy.index12306.biz.orderservice.service.OrderCommandPersistenceService;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.springframework.stereotype.Component;

/**
 * 消费票务服务发布的订单创建命令，并在确认 MQ 前把完整任务可靠交给 Command 状态机。
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

    private final OrderCommandPersistenceService orderCommandPersistenceService;
    private final OrderCommandExecutionService orderCommandExecutionService;

    /**
     * 先持久化完整订单任务，再尝试异步唤醒执行器；返回即表示 MQ 可以安全确认消息。
     *
     * @param message 票务服务持久化后发布的订单创建命令
     */
    @Override
    public void onMessage(TicketOrderCreateCommandMessage message) {
        try {
            // Command 的本地事务提交是消息所有权转移边界；失败时抛出异常让 RocketMQ 重投。
            OrderCommandDO command = orderCommandPersistenceService.persistReceivedCommand(message);
            // 即时调度只是降低延迟，线程池拒绝或进程退出时仍由持久化恢复扫描重新发现任务。
            orderCommandExecutionService.dispatchAsync(command.getUserId(), command.getCommandId());
            log.info("ticket_order_create_command_persisted reservationId={}, commandId={}, status={}",
                    message.getReservationId(), message.getCommandId(), command.getStatus());
        } catch (RuntimeException exception) {
            log.error("ticket_order_create_command_persist_failed messageId={}, commandId={}",
                    message == null ? null : message.getMessageId(),
                    message == null ? null : message.getCommandId(), exception);
            throw exception;
        }
    }
}
