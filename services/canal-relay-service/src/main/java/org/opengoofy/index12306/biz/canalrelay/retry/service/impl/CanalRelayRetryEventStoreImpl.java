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

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.canalrelay.retry.dao.entity.CanalRelayRetryEventDO;
import org.opengoofy.index12306.biz.canalrelay.retry.dao.mapper.CanalRelayRetryEventMapper;
import org.opengoofy.index12306.biz.canalrelay.retry.model.CanalRelayRetryEvent;
import org.opengoofy.index12306.biz.canalrelay.retry.service.CanalRelayRetryEventStore;
import org.opengoofy.index12306.biz.canalrelay.retry.security.CanalRelayPayloadProtector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Relay 失败事件的事务持久化实现。
 */
@Service
@RequiredArgsConstructor
public class CanalRelayRetryEventStoreImpl implements CanalRelayRetryEventStore {

    private static final int MAX_ERROR_LENGTH = 512;

    private final CanalRelayRetryEventMapper retryEventMapper;
    private final CanalRelayPayloadProtector payloadProtector;

    /**
     * 将失败事件批量转换为密文记录并以 retryKey 做幂等接管。
     *
     * @param events 当前批次需要隔离的失败事件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAll(Collection<CanalRelayRetryEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        // 所有事件在同一事务内写入，任意一条数据库异常都会阻止后续 Canal ACK。
        for (CanalRelayRetryEvent event : events) {
            saveOne(event);
        }
    }

    /**
     * 校验并幂等保存一条失败事件。
     *
     * @param event 待保存的失败事件
     */
    private void saveOne(CanalRelayRetryEvent event) {
        validate(event);
        String payloadFingerprint = fingerprint(event.getPayload());
        String payloadCiphertext = payloadProtector.protect(event.getPayload());
        Date now = new Date();
        CanalRelayRetryEventDO record = toRecord(event, payloadCiphertext, payloadFingerprint, now);

        // INSERT IGNORE 只负责原子抢占唯一键，命中旧记录后仍需核对不可变事实。
        if (retryEventMapper.insertIgnore(record) == 0) {
            CanalRelayRetryEventDO existing = retryEventMapper.selectByRetryKey(event.getRetryKey());
            if (existing == null) {
                throw new IllegalStateException("Relay 重试任务唯一键冲突但无法读取原记录");
            }
            requireSameEvent(existing, record);
        }
    }

    /**
     * 将内存事件转换为只包含密文载荷的数据库记录。
     *
     * @param event 内存失败事件
     * @param payloadCiphertext 已保护载荷
     * @param payloadFingerprint 明文载荷摘要
     * @param now 当前时间
     * @return 数据库记录
     */
    private CanalRelayRetryEventDO toRecord(
            CanalRelayRetryEvent event, String payloadCiphertext, String payloadFingerprint, Date now) {
        return CanalRelayRetryEventDO.builder()
                .retryKey(event.getRetryKey())
                .namespace(event.getNamespace())
                .eventId(event.getEventId())
                .commandId(event.getCommandId())
                .aggregateId(event.getAggregateId())
                .destinationTopic(event.getDestinationTopic())
                .destinationTag(event.getDestinationTag())
                .delayLevel(event.getDelayLevel())
                .payloadCiphertext(payloadCiphertext)
                .payloadFingerprint(payloadFingerprint)
                .payloadKeyVersion(payloadProtector.currentKeyVersion())
                .status(event.getStatus().name())
                .attemptCount(event.getAttemptCount())
                .maxAttempts(event.getMaxAttempts())
                .nextRetryTime(event.getNextRetryTime())
                .lastError(truncate(event.getLastError()))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 校验失败事件可被独立恢复且不超出数据库字段边界。
     *
     * @param event 待校验事件
     */
    private void validate(CanalRelayRetryEvent event) {
        if (event == null
                || !hasText(event.getRetryKey())
                || !hasText(event.getNamespace())
                || !hasText(event.getEventId())
                || !hasText(event.getAggregateId())
                || !hasText(event.getDestinationTopic())
                || !hasText(event.getDestinationTag())
                || event.getPayload() == null
                || event.getStatus() == null
                || event.getAttemptCount() == null
                || event.getMaxAttempts() == null
                || event.getNextRetryTime() == null
                || event.getAttemptCount() < 0
                || event.getMaxAttempts() <= 0) {
            throw new IllegalArgumentException("Relay 重试事件缺少持久化所需字段");
        }
        // 载荷可以是空 JSON，但不能以空白字符串占位，避免保存无法恢复的任务。
        if (event.getPayload().isBlank()) {
            throw new IllegalArgumentException("Relay 重试事件载荷不能为空");
        }
        checkLength(event.getRetryKey(), 191, "retryKey");
        checkLength(event.getNamespace(), 64, "namespace");
        checkLength(event.getEventId(), 128, "eventId");
        checkLength(event.getCommandId(), 160, "commandId");
        checkLength(event.getAggregateId(), 128, "aggregateId");
        checkLength(event.getDestinationTopic(), 128, "destinationTopic");
        checkLength(event.getDestinationTag(), 64, "destinationTag");
    }

    /**
     * 校验唯一键命中的记录仍对应同一条不可变业务事件。
     *
     * @param existing 数据库中的旧记录
     * @param incoming 本次准备写入的记录
     */
    private void requireSameEvent(CanalRelayRetryEventDO existing, CanalRelayRetryEventDO incoming) {
        if (!Objects.equals(existing.getRetryKey(), incoming.getRetryKey())
                || !Objects.equals(existing.getNamespace(), incoming.getNamespace())
                || !Objects.equals(existing.getEventId(), incoming.getEventId())
                || !Objects.equals(existing.getCommandId(), incoming.getCommandId())
                || !Objects.equals(existing.getAggregateId(), incoming.getAggregateId())
                || !Objects.equals(existing.getDestinationTopic(), incoming.getDestinationTopic())
                || !Objects.equals(existing.getDestinationTag(), incoming.getDestinationTag())
                || !Objects.equals(existing.getDelayLevel(), incoming.getDelayLevel())
                || !Objects.equals(existing.getPayloadFingerprint(), incoming.getPayloadFingerprint())) {
            throw new IllegalStateException("Relay 重试任务唯一键对应的不可变事件不一致");
        }
    }

    /**
     * 计算明文载荷摘要，避免为重复校验解密旧记录。
     *
     * @param payload 明文载荷
     * @return SHA-256 十六进制摘要
     */
    private String fingerprint(String payload) {
        try {
            // 摘要只用于一致性校验，不应替代密文，也不能写入日志。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * 截断错误摘要，避免底层异常正文扩大敏感信息暴露面。
     *
     * @param value 原始错误摘要
     * @return 限长后的错误摘要
     */
    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * 校验字段长度。
     *
     * @param value 字段值
     * @param maxLength 最大长度
     * @param fieldName 字段名称
     */
    private void checkLength(String value, int maxLength, String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException("Relay 重试事件字段超过长度限制: " + fieldName);
        }
    }

    /**
     * 判断字符串是否包含有效文本。
     *
     * @param value 待检查字符串
     * @return 存在非空白字符时返回 true
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
