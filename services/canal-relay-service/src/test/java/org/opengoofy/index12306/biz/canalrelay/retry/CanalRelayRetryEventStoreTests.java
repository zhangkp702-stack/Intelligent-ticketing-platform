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

package org.opengoofy.index12306.biz.canalrelay.retry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.canalrelay.retry.dao.entity.CanalRelayRetryEventDO;
import org.opengoofy.index12306.biz.canalrelay.retry.dao.mapper.CanalRelayRetryEventMapper;
import org.opengoofy.index12306.biz.canalrelay.retry.model.CanalRelayRetryEvent;
import org.opengoofy.index12306.biz.canalrelay.retry.model.CanalRelayRetryStatus;
import org.opengoofy.index12306.biz.canalrelay.retry.security.AesGcmCanalRelayPayloadProtector;
import org.opengoofy.index12306.biz.canalrelay.retry.security.CanalRelayPayloadProtector;
import org.opengoofy.index12306.biz.canalrelay.retry.service.impl.CanalRelayRetryEventStoreImpl;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 验证 Relay 失败事件的加密、幂等和冲突保护边界。
 */
@ExtendWith(MockitoExtension.class)
class CanalRelayRetryEventStoreTests {

    @Mock
    private CanalRelayRetryEventMapper retryEventMapper;

    @Mock
    private CanalRelayPayloadProtector payloadProtector;

    private CanalRelayRetryEventStoreImpl eventStore;

    /**
     * 为每个用例创建只依赖 Mapper 和载荷保护器的事务服务。
     */
    @BeforeEach
    void setUp() {
        // 单元测试直接构造实现，事务边界由 Spring 注解在集成运行时提供。
        eventStore = new CanalRelayRetryEventStoreImpl(retryEventMapper, payloadProtector);
        lenient().when(payloadProtector.protect("sensitive-payload")).thenReturn("ciphertext");
        lenient().when(payloadProtector.currentKeyVersion()).thenReturn(3);
    }

    /**
     * 验证新失败任务只保存密文、摘要和可恢复的路由字段。
     */
    @Test
    void encryptsPayloadBeforePersistingRetryEvent() {
        when(retryEventMapper.insertIgnore(any())).thenReturn(1);

        eventStore.saveAll(List.of(event(CanalRelayRetryStatus.RETRY)));

        ArgumentCaptor<CanalRelayRetryEventDO> captor = ArgumentCaptor.forClass(CanalRelayRetryEventDO.class);
        verify(retryEventMapper).insertIgnore(captor.capture());
        CanalRelayRetryEventDO record = captor.getValue();
        assertThat(record.getPayloadCiphertext()).isEqualTo("ciphertext");
        assertThat(record.getPayloadCiphertext()).doesNotContain("sensitive-payload");
        assertThat(record.getPayloadFingerprint()).hasSize(64);
        assertThat(record.getPayloadKeyVersion()).isEqualTo(3);
        assertThat(record.getStatus()).isEqualTo(CanalRelayRetryStatus.RETRY.name());
    }

    /**
     * 验证 Canal 重放命中唯一键时，完全一致的事件可以安全复用旧任务。
     */
    @Test
    void reusesDuplicateEventWhenImmutableFieldsMatch() {
        when(retryEventMapper.insertIgnore(any())).thenReturn(0);
        when(retryEventMapper.selectByRetryKey(eq("ticket:event-1"))).thenReturn(existingRecord());

        eventStore.saveAll(List.of(event(CanalRelayRetryStatus.RETRY)));

        verify(retryEventMapper).selectByRetryKey("ticket:event-1");
    }

    /**
     * 验证同一 retryKey 绑定不同载荷时拒绝覆盖原任务。
     */
    @Test
    void rejectsDuplicateEventWhenPayloadFingerprintDiffers() {
        when(retryEventMapper.insertIgnore(any())).thenReturn(0);
        CanalRelayRetryEventDO existing = existingRecord();
        existing.setPayloadFingerprint("different-fingerprint");
        when(retryEventMapper.selectByRetryKey(eq("ticket:event-1"))).thenReturn(existing);

        assertThatThrownBy(() -> eventStore.saveAll(List.of(event(CanalRelayRetryStatus.RETRY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可变事件不一致");
    }

    /**
     * 验证 AES-GCM 保护器能恢复载荷且每次加密使用不同随机 IV。
     */
    @Test
    void protectsAndRestoresPayloadWithRandomIv() {
        String encodedKey = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        AesGcmCanalRelayPayloadProtector protector =
                new AesGcmCanalRelayPayloadProtector(encodedKey, 1);

        String first = protector.protect("sensitive-payload");
        String second = protector.protect("sensitive-payload");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotContain("sensitive-payload");
        assertThat(protector.restore(first, 1)).isEqualTo("sensitive-payload");
    }

    /**
     * 构造测试失败事件。
     *
     * @param status 任务状态
     * @return 带稳定路由标识的失败事件
     */
    private CanalRelayRetryEvent event(CanalRelayRetryStatus status) {
        return CanalRelayRetryEvent.builder()
                .retryKey("ticket:event-1")
                .namespace("ticket-order")
                .eventId("event-1")
                .commandId("purchase-1:create-order")
                .aggregateId("reservation-1")
                .destinationTopic("ticket-order-create")
                .destinationTag("create-order")
                .delayLevel(0)
                .payload("sensitive-payload")
                .status(status)
                .attemptCount(3)
                .maxAttempts(16)
                .nextRetryTime(new Date())
                .lastError("temporary timeout")
                .build();
    }

    /**
     * 构造与测试事件一致的已有数据库记录。
     *
     * @return 已保存的失败任务
     */
    private CanalRelayRetryEventDO existingRecord() {
        CanalRelayRetryEventDO record = CanalRelayRetryEventDO.builder()
                .retryKey("ticket:event-1")
                .namespace("ticket-order")
                .eventId("event-1")
                .commandId("purchase-1:create-order")
                .aggregateId("reservation-1")
                .destinationTopic("ticket-order-create")
                .destinationTag("create-order")
                .delayLevel(0)
                .payloadFingerprint(payloadFingerprint("sensitive-payload"))
                .build();
        return record;
    }

    /**
     * 计算测试记录的明文载荷摘要。
     *
     * @param payload 明文载荷
     * @return SHA-256 十六进制摘要
     */
    private String payloadFingerprint(String payload) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
