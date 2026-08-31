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

package org.opengoofy.index12306.biz.canalrelay.retry.service.impl;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.opengoofy.index12306.biz.canalrelay.config.CanalRelayProperties;
import org.opengoofy.index12306.biz.canalrelay.retry.dao.entity.CanalRelayRetryEventDO;
import org.opengoofy.index12306.biz.canalrelay.retry.dao.mapper.CanalRelayRetryEventMapper;
import org.opengoofy.index12306.biz.canalrelay.retry.model.CanalRelayRetryStatus;
import org.opengoofy.index12306.biz.canalrelay.retry.security.CanalRelayPayloadProtector;
import org.opengoofy.index12306.biz.canalrelay.retry.service.CanalRelayRetryTaskService;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateResultMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderDelayCloseMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.springframework.stereotype.Service;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Canal Relay 失败任务的逐条租约恢复实现。
 */
@Slf4j
@Service
public class CanalRelayRetryTaskServiceImpl implements CanalRelayRetryTaskService {

    private static final int MAX_ERROR_LENGTH = 512;

    private final CanalRelayRetryEventMapper retryEventMapper;
    private final CanalRelayPayloadProtector payloadProtector;
    private final RocketMQTemplate rocketMQTemplate;
    private final CanalRelayProperties properties;
    private final String owner;

    /**
     * 创建带实例级租约持有者的后台恢复服务。
     *
     * @param retryEventMapper 失败任务持久层
     * @param payloadProtector 重试载荷解密器
     * @param rocketMQTemplate RocketMQ 发送器
     * @param properties Relay 重试配置
     */
    public CanalRelayRetryTaskServiceImpl(
            CanalRelayRetryEventMapper retryEventMapper,
            CanalRelayPayloadProtector payloadProtector,
            RocketMQTemplate rocketMQTemplate,
            CanalRelayProperties properties) {
        this.retryEventMapper = retryEventMapper;
        this.payloadProtector = payloadProtector;
        this.rocketMQTemplate = rocketMQTemplate;
        this.properties = properties;
        this.owner = "relay-" + UUID.randomUUID();
    }

    /**
     * 扫描到期任务，并通过数据库条件更新逐条抢占处理权。
     */
    @Override
    public void scanAndRetry() {
        int batchSize = Math.max(1, properties.getRetryBatchSize());
        List<CanalRelayRetryEventDO> candidates = retryEventMapper.selectDue(batchSize);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        // 候选只是一致性快照，真正执行前必须再次以 owner 条件原子抢占。
        for (CanalRelayRetryEventDO candidate : candidates) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            Date leaseUntil = new Date(System.currentTimeMillis() + normalizedLeaseMillis());
            if (retryEventMapper.claim(candidate.getId(), owner, leaseUntil) != 1) {
                continue;
            }
            // 抢占后重新读取最新次数和载荷元数据，避免并发实例刚释放任务造成旧快照覆盖。
            CanalRelayRetryEventDO claimed = retryEventMapper.selectById(candidate.getId());
            if (claimed == null) {
                log.warn("canal_relay_retry_claimed_record_missing id={}", candidate.getId());
                continue;
            }
            processClaimed(claimed);
        }
    }

    /**
     * 处理已获得租约的一条任务，成功删除，失败释放租约并更新状态。
     *
     * @param record 已被当前实例抢占的任务快照
     */
    private void processClaimed(CanalRelayRetryEventDO record) {
        Object payload;
        try {
            // 解密后按稳定业务域恢复为消费者声明的具体消息类型，不能以普通字符串投递。
            payload = restorePayload(record);
        } catch (RuntimeException exception) {
            // 密文损坏、密钥版本不支持或协议未知都无法靠重复发送修复，直接标记 DEAD。
            recordFailure(record, exception, true);
            return;
        }
        try {
            publish(record, payload);
        } catch (RuntimeException exception) {
            recordFailure(record, exception, false);
            return;
        }
        try {
            if (retryEventMapper.deleteClaimed(record.getId(), owner) != 1) {
                // 发送已成功但租约可能已过期；下次恢复可能重复投递，依赖下游幂等键兜底。
                log.warn("canal_relay_retry_success_cleanup_skipped retryKey={}", record.getRetryKey());
            }
        } catch (RuntimeException exception) {
            // 不把清理异常伪装成发送失败，避免重复增加 attempt_count；租约到期后会再次扫描。
            log.error("canal_relay_retry_success_cleanup_failed retryKey={}, exceptionType={}",
                    record.getRetryKey(), exception.getClass().getSimpleName(), exception);
        }
    }

    /**
     * 解密并按命名空间还原共享订单消息。
     *
     * @param record 加密重试任务
     * @return 可直接交给 RocketMQ 转换器的强类型消息
     */
    private Object restorePayload(CanalRelayRetryEventDO record) {
        String payload = payloadProtector.restore(record.getPayloadCiphertext(), record.getPayloadKeyVersion());
        if (TicketOrderMessageConstants.CREATE_ORDER_NAMESPACE.equals(record.getNamespace())) {
            return parsePayload(payload, TicketOrderCreateCommandMessage.class);
        }
        if (TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE.equals(record.getNamespace())) {
            return parsePayload(payload, TicketOrderCreateResultMessage.class);
        }
        if (TicketOrderMessageConstants.DELAY_CLOSE_ORDER_NAMESPACE.equals(record.getNamespace())) {
            return parsePayload(payload, TicketOrderDelayCloseMessage.class);
        }
        throw new IllegalStateException("Relay 重试任务命名空间不受支持");
    }

    /**
     * 将 JSON 载荷恢复为指定跨服务协议对象。
     *
     * @param payload 已解密 JSON
     * @param payloadType 目标消息类型
     * @return 反序列化后的消息
     */
    private <T> T parsePayload(String payload, Class<T> payloadType) {
        T message = JSON.parseObject(payload, payloadType);
        if (message == null) {
            throw new IllegalStateException("Relay 重试任务载荷为空");
        }
        return message;
    }

    /**
     * 将强类型消息发送到原始 Topic、Tag 和延迟等级。
     *
     * @param record 重试任务元数据
     * @param payload 已恢复的强类型消息
     */
    private void publish(CanalRelayRetryEventDO record, Object payload) {
        int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, properties.getRetrySendTimeoutMillis()));
        int delayLevel = Math.max(0, record.getDelayLevel() == null ? 0 : record.getDelayLevel());
        String destination = record.getDestinationTopic() + ":" + record.getDestinationTag();
        SendResult sendResult;
        if (delayLevel > 0) {
            // RocketMQ 的延迟重载要求 Message，使用强类型 payload 保持消费者反序列化协议不变。
            Message<Object> message = MessageBuilder.withPayload(payload).build();
            sendResult = rocketMQTemplate.syncSend(destination, message, timeout, delayLevel);
        } else {
            sendResult = rocketMQTemplate.syncSend(destination, payload);
        }
        if (sendResult == null || sendResult.getMsgId() == null || sendResult.getMsgId().isBlank()) {
            throw new IllegalStateException("RocketMQ 未确认 Relay 重试任务");
        }
    }

    /**
     * 更新一次失败任务结果，并在达到上限时转为 DEAD。
     *
     * @param record 当前租约任务
     * @param exception 本次失败异常
     * @param permanent 是否为不可自动修复的永久失败
     */
    private void recordFailure(CanalRelayRetryEventDO record, RuntimeException exception, boolean permanent) {
        int currentAttempts = Math.max(0, record.getAttemptCount() == null ? 0 : record.getAttemptCount());
        int attempts = currentAttempts == Integer.MAX_VALUE ? Integer.MAX_VALUE : currentAttempts + 1;
        int maxAttempts = Math.max(1, record.getMaxAttempts() == null ? 1 : record.getMaxAttempts());
        boolean dead = permanent || attempts >= maxAttempts;
        String status = dead ? CanalRelayRetryStatus.DEAD.name() : CanalRelayRetryStatus.RETRY.name();
        Date nextRetryTime = dead ? new Date() : new Date(System.currentTimeMillis() + retryBackoffMillis(attempts));
        try {
            if (retryEventMapper.releaseAfterFailure(
                    record.getId(), owner, status, attempts, nextRetryTime, errorSummary(exception)) != 1) {
                log.warn("canal_relay_retry_failure_release_skipped retryKey={}", record.getRetryKey());
            }
        } catch (RuntimeException updateException) {
            // 更新失败时保留租约直到自然过期，避免并发实例立即重复发送同一任务。
            log.error("canal_relay_retry_failure_release_failed retryKey={}, exceptionType={}",
                    record.getRetryKey(), updateException.getClass().getSimpleName(), updateException);
        }
    }

    /**
     * 计算指数退避并限制最大等待时间。
     *
     * @param attempts 累计失败次数
     * @return 本次等待毫秒数
     */
    private long retryBackoffMillis(int attempts) {
        long initial = Math.max(1L, properties.getRetryInitialBackoffMillis());
        long maximum = Math.max(initial, properties.getRetryMaxBackoffMillis());
        int shift = Math.min(10, Math.max(0, attempts - 1));
        long multiplier = 1L << shift;
        return initial > maximum / multiplier ? maximum : initial * multiplier;
    }

    /**
     * 规范化租约时长，避免零值造成并发实例立即接管。
     *
     * @return 至少一毫秒的租约时长
     */
    private long normalizedLeaseMillis() {
        return Math.max(1L, properties.getRetryLeaseMillis());
    }

    /**
     * 生成不包含订单消息载荷的异常摘要。
     *
     * @param exception 当前异常
     * @return 异常类型和受限消息文本
     */
    private String errorSummary(RuntimeException exception) {
        if (exception == null) {
            return "unknown";
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String summary = (exception.getClass().getSimpleName() + ": " + message)
                .replace('\n', ' ')
                .replace('\r', ' ');
        return summary.length() <= MAX_ERROR_LENGTH ? summary : summary.substring(0, MAX_ERROR_LENGTH);
    }
}
