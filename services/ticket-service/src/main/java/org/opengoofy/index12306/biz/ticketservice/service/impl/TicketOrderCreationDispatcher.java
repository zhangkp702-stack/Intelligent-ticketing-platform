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
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketOrderCreationMetrics;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketOrderCreationMetrics.DispatchOutcome;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventLease;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableOutboxRecord;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.opengoofy.index12306.biz.ticketservice.service.impl.TicketSeatReservationReleaseService.ORDER_CREATION_EVENT_NAMESPACE;

/**
 * 从本地 Outbox 可恢复地向 RocketMQ 派发订单创建命令。
 */
@Slf4j
@Service
public class TicketOrderCreationDispatcher {

    private final ReliableEventStore reliableEventStore;
    private final RocketMQTemplate rocketMQTemplate;
    private final TicketOrderCreationMetrics ticketOrderCreationMetrics;
    private final Executor executor;
    private final String workerId = "ticket-order-creation-" + UUID.randomUUID();

    @Value("${index12306.ticket.async-order.batch-size:32}")
    private int batchSize;

    @Value("${index12306.ticket.async-order.lease-millis:30000}")
    private long leaseMillis;

    @Value("${index12306.ticket.async-order.retry-base-millis:200}")
    private long retryBaseMillis;

    @Value("${index12306.ticket.async-order.retry-max-millis:5000}")
    private long retryMaxMillis;

    /**
     * 创建异步建单派发器。
     *
     * @param reliableEventStore 本地可靠事件存储
     * @param rocketMQTemplate 订单创建命令消息发送器
     * @param ticketOrderCreationMetrics 异步建单指标记录器
     * @param executor 异步建单专用执行器
     */
    public TicketOrderCreationDispatcher(
            ReliableEventStore reliableEventStore,
            RocketMQTemplate rocketMQTemplate,
            TicketOrderCreationMetrics ticketOrderCreationMetrics,
            @Qualifier("ticketOrderCreationExecutor") Executor executor) {
        this.reliableEventStore = reliableEventStore;
        this.rocketMQTemplate = rocketMQTemplate;
        this.ticketOrderCreationMetrics = ticketOrderCreationMetrics;
        this.executor = executor;
    }

    /**
     * 回收过期租约并并发派发一批待创建订单。
     */
    @Scheduled(fixedDelayString = "${index12306.ticket.async-order.interval-millis:200}")
    public void dispatchPendingOrders() {
        try {
            Instant now = Instant.now();
            int normalizedBatchSize = Math.max(1, batchSize);
            // 先回收宕机实例遗留的过期租约，再用围栏令牌认领当前批次。
            int recoveredCount = reliableEventStore.recoverExpiredPublications(
                    ORDER_CREATION_EVENT_NAMESPACE, now, normalizedBatchSize);
            ticketOrderCreationMetrics.recordLeaseRecovered(recoveredCount);
            List<ReliableOutboxRecord> claimed = reliableEventStore.claimPublishable(
                    ORDER_CREATION_EVENT_NAMESPACE,
                    workerId,
                    now,
                    now.plusMillis(Math.max(1000L, leaseMillis)),
                    normalizedBatchSize);
            if (claimed.isEmpty()) {
                return;
            }
            ticketOrderCreationMetrics.recordClaimed(claimed.size());
            // 同一批次并发发送命令消息，不持有票务数据库事务或 HTTP 请求线程。
            List<CompletableFuture<?>> futures = new ArrayList<>(claimed.size());
            for (ReliableOutboxRecord event : claimed) {
                try {
                    futures.add(CompletableFuture.runAsync(() -> dispatchOne(event), executor));
                } catch (RejectedExecutionException ex) {
                    // 任务尚未执行时立即交还当前租约，避免无意义地等待完整租约超时。
                    rescheduleRejectedEvent(event, ex);
                }
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (RuntimeException ex) {
            // 调度周期失败时下轮仍可继续，指标与日志用于区分单事件失败和存储层故障。
            ticketOrderCreationMetrics.recordDispatchCycleFailure();
            log.error("ticket_async_order_dispatch_cycle_failed exceptionType={}", ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * 将稳定建单命令发送到 RocketMQ，并在 Broker 确认后提交 Outbox 围栏。
     *
     * @param event 已取得当前实例租约的建单事件
     */
    private void dispatchOne(ReliableOutboxRecord event) {
        ticketOrderCreationMetrics.recordDispatchWait(event.createdAt());
        try {
            // 载荷是锁座事务中固定的命令；旧载荷只转换为同一条 MQ 命令，不再走 Feign 建单。
            TicketOrderCreateCommandMessage message = parseCommandMessage(event);
            SendResult sendResult = rocketMQTemplate.syncSend(
                    TicketOrderMessageConstants.CREATE_ORDER_TOPIC + ":"
                            + TicketOrderMessageConstants.CREATE_ORDER_TAG,
                    message);
            if (sendResult == null || sendResult.getMsgId() == null || sendResult.getMsgId().isBlank()) {
                throw new ServiceException("RocketMQ 未返回订单创建消息标识");
            }
            // Broker 已确认后即可完成票务 Outbox；订单最终状态由结果消息消费者异步绑定或释放。
            recordPublishResult(event, sendResult.getMsgId(), DispatchOutcome.PUBLISHED);
            log.info("ticket_order_create_command_published reservationId={}, commandId={}, attempt={}",
                    message.getReservationId(), message.getCommandId(), event.publishAttemptCount());
        } catch (RuntimeException ex) {
            // 发送失败不释放座位；同一 commandId 和消息标识将在下一次 Outbox 重试中保持不变。
            long retryDelay = retryDelayMillis(event.publishAttemptCount());
            boolean rescheduled = reliableEventStore.markPublishFailed(
                    event.key(),
                    requireLease(event),
                    event.eventVersion(),
                    "ORDER_CREATE_COMMAND_PUBLISH_RETRY",
                    ex.getClass().getSimpleName() + ": " + safeMessage(ex),
                    Instant.now().plusMillis(retryDelay),
                    Instant.now());
            log.warn("ticket_order_create_command_publish_retry reservationId={}, attempt={}, retryDelayMillis={}, rescheduled={}, exceptionType={}",
                    event.aggregateId(), event.publishAttemptCount(), retryDelay, rescheduled,
                    ex.getClass().getSimpleName());
            ticketOrderCreationMetrics.recordDispatchOutcome(DispatchOutcome.RETRY);
        }
    }

    /**
     * 读取当前 Outbox 载荷，并将历史内部 DTO 转换为统一的订单创建 MQ 命令。
     *
     * @param event 已取得发布租约的建单 Outbox 事件
     * @return 字段完整且与 Outbox 主键一致的共享建单命令
     */
    private TicketOrderCreateCommandMessage parseCommandMessage(ReliableOutboxRecord event) {
        JSONObject payload = JSON.parseObject(event.payload());
        if (payload == null) {
            throw new ServiceException("异步建单事件载荷为空");
        }
        TicketOrderCreateCommandMessage message;
        if (payload.containsKey("request")) {
            // 新写入的事件已使用共享协议，直接反序列化以保持重放载荷完全不变。
            message = JSON.parseObject(event.payload(), TicketOrderCreateCommandMessage.class);
        } else {
            // 仅转换已经持久化的旧 DTO，兼容未发送事件但不恢复任何 Feign 建单分支。
            TicketOrderCreateRemoteReqDTO legacyRequest = JSON.parseObject(
                    event.payload(), TicketOrderCreateRemoteReqDTO.class);
            message = convertLegacyMessage(event, legacyRequest);
        }
        if (!isValidCommandMessage(message, event)) {
            throw new ServiceException("异步建单事件缺少有效 MQ 命令字段");
        }
        return message;
    }

    /**
     * 将历史票务内部建单 DTO 映射为当前共享 MQ 协议。
     *
     * @param event 关联的历史 Outbox 事件
     * @param source 历史持久化的票务内部请求快照
     * @return 与原稳定命令绑定的共享建单消息
     */
    private TicketOrderCreateCommandMessage convertLegacyMessage(
            ReliableOutboxRecord event,
            TicketOrderCreateRemoteReqDTO source) {
        if (source == null || source.getTicketOrderItems() == null || source.getTicketOrderItems().isEmpty()) {
            throw new ServiceException("历史异步建单事件缺少车票明细");
        }
        // 历史消息只从原载荷复制，绝不查询当前票价、乘车人或座位以避免重试改变订单内容。
        List<TicketOrderCreateItem> items = source.getTicketOrderItems().stream()
                .map(item -> TicketOrderCreateItem.builder()
                        .carriageNumber(item.getCarriageNumber())
                        .seatType(item.getSeatType())
                        .seatNumber(item.getSeatNumber())
                        .realName(item.getRealName())
                        .idType(item.getIdType())
                        .idCard(item.getIdCard())
                        .phone(item.getPhone())
                        .amount(item.getAmount())
                        .ticketType(item.getTicketType())
                        .build())
                .toList();
        TicketOrderCreateRequest request = TicketOrderCreateRequest.builder()
                .userId(source.getUserId())
                .username(source.getUsername())
                .trainId(source.getTrainId())
                .departure(source.getDeparture())
                .arrival(source.getArrival())
                .source(source.getSource())
                .orderTime(source.getOrderTime())
                .ridingDate(source.getRidingDate())
                .trainNumber(source.getTrainNumber())
                .departureTime(source.getDepartureTime())
                .arrivalTime(source.getArrivalTime())
                .ticketOrderItems(items)
                .build();
        // 历史 Outbox eventId 与 aggregateId 已分别是消息 ID 和 reservationId，可直接作为共享协议主键。
        return TicketOrderCreateCommandMessage.builder()
                .messageId(event.key().eventId())
                .messageVersion(event.eventVersion())
                .reservationId(event.aggregateId())
                .actionId(source.getActionId())
                .commandId(source.getCommandId())
                .request(request)
                .build();
    }

    /**
     * 校验命令版本及主键与当前 Outbox 记录一致，防止错误载荷进入订单服务。
     *
     * @param message 待发送的订单创建命令
     * @param event 当前 Outbox 事件
     * @return 所有稳定关联字段完整且版本匹配时返回 true
     */
    private boolean isValidCommandMessage(TicketOrderCreateCommandMessage message, ReliableOutboxRecord event) {
        return message != null
                && message.getMessageVersion() != null
                && message.getMessageVersion() == event.eventVersion()
                && message.getMessageVersion() == TicketOrderMessageConstants.CREATE_ORDER_VERSION
                && event.key().eventId().equals(message.getMessageId())
                && event.aggregateId().equals(message.getReservationId())
                && hasText(message.getActionId())
                && (message.getActionId() + ":create-order").equals(message.getCommandId())
                && message.getRequest() != null
                && hasText(message.getRequest().getUserId())
                && message.getRequest().getTicketOrderItems() != null
                && !message.getRequest().getTicketOrderItems().isEmpty();
    }

    /**
     * 用当前租约围栏完成建单事件，并记录成功或围栏丢失结果。
     *
     * @param event 已认领事件
     * @param brokerMessageId RocketMQ 返回的消息标识
     * @param successOutcome 围栏提交成功时应记录的固定结果
     */
    private void recordPublishResult(
            ReliableOutboxRecord event,
            String brokerMessageId,
            DispatchOutcome successOutcome) {
        // 围栏更新失败代表租约已被新实例接管，不能覆盖新实例的处理结果。
        boolean published = reliableEventStore.markPublished(
                event.key(), requireLease(event), event.eventVersion(), brokerMessageId, Instant.now());
        if (!published) {
            log.warn("ticket_async_order_publish_fence_lost reservationId={}, attempt={}",
                    event.aggregateId(), event.publishAttemptCount());
        }
        ticketOrderCreationMetrics.recordDispatchOutcome(published ? successOutcome : DispatchOutcome.FENCE_LOST);
    }

    /**
     * 将未被线程池接收的事件立即退回 Outbox，并保留当前失败分类供人工核对。
     *
     * @param event 已取得当前实例租约但尚未执行的事件
     * @param exception 执行器拒绝异常
     */
    private void rescheduleRejectedEvent(ReliableOutboxRecord event, RejectedExecutionException exception) {
        long retryDelay = retryDelayMillis(event.publishAttemptCount());
        boolean rescheduled = reliableEventStore.markPublishFailed(
                event.key(),
                requireLease(event),
                event.eventVersion(),
                "ORDER_CREATE_EXECUTOR_REJECTED",
                safeMessage(exception),
                Instant.now().plusMillis(retryDelay),
                Instant.now());
        // 拒绝事件与 Broker 发送失败分开统计，便于区分本地容量不足和消息服务异常。
        ticketOrderCreationMetrics.recordDispatchOutcome(DispatchOutcome.EXECUTOR_REJECTED);
        log.warn("ticket_async_order_executor_rejected reservationId={}, attempt={}, retryDelayMillis={}, rescheduled={}",
                event.aggregateId(), event.publishAttemptCount(), retryDelay, rescheduled);
    }

    /**
     * 计算有上限的指数退避时间。
     *
     * @param attemptCount 已认领次数
     * @return 下次派发前的等待毫秒数
     */
    private long retryDelayMillis(int attemptCount) {
        long base = Math.max(1L, retryBaseMillis);
        long maximum = Math.max(base, retryMaxMillis);
        int exponent = Math.min(Math.max(0, attemptCount - 1), 10);
        return Math.min(maximum, base * (1L << exponent));
    }

    /**
     * 获取已认领事件的完整租约。
     *
     * @param event 已认领事件
     * @return 可用于围栏提交的租约
     */
    private ReliableEventLease requireLease(ReliableOutboxRecord event) {
        if (event.lease() == null) {
            throw new IllegalStateException("异步建单事件缺少发布租约");
        }
        return event.lease();
    }

    /**
     * 生成不包含业务载荷的限长错误摘要。
     *
     * @param throwable 当前派发异常
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
