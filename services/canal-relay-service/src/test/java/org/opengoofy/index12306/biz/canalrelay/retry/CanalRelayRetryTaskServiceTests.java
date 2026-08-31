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

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.canalrelay.config.CanalRelayProperties;
import org.opengoofy.index12306.biz.canalrelay.retry.dao.entity.CanalRelayRetryEventDO;
import org.opengoofy.index12306.biz.canalrelay.retry.dao.mapper.CanalRelayRetryEventMapper;
import org.opengoofy.index12306.biz.canalrelay.retry.security.CanalRelayPayloadProtector;
import org.opengoofy.index12306.biz.canalrelay.retry.service.impl.CanalRelayRetryTaskServiceImpl;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * 验证 Relay 后台失败任务的租约、强类型恢复和状态迁移边界。
 */
@ExtendWith(MockitoExtension.class)
class CanalRelayRetryTaskServiceTests {

    @Mock
    private CanalRelayRetryEventMapper retryEventMapper;

    @Mock
    private CanalRelayPayloadProtector payloadProtector;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private CanalRelayProperties properties;
    private CanalRelayRetryTaskServiceImpl retryTaskService;

    /**
     * 创建不依赖 Spring 容器的后台重试服务。
     */
    @BeforeEach
    void setUp() {
        properties = new CanalRelayProperties();
        properties.setRetryBatchSize(32);
        properties.setRetryLeaseMillis(1000L);
        properties.setRetryInitialBackoffMillis(10L);
        properties.setRetryMaxBackoffMillis(100L);
        properties.setRetrySendTimeoutMillis(2000L);
        retryTaskService = new CanalRelayRetryTaskServiceImpl(
                retryEventMapper, payloadProtector, rocketMQTemplate, properties);
        lenient().when(payloadProtector.restore("ciphertext", 1)).thenReturn("{}");
        lenient().when(retryEventMapper.releaseAfterFailure(
                any(), anyString(), anyString(), anyInt(), any(Date.class), anyString())).thenReturn(1);
    }

    /**
     * 只有抢占租约成功的实例才可恢复任务，成功投递后删除任务记录。
     */
    @Test
    void claimsAndDeletesTaskAfterSuccessfulPublish() {
        CanalRelayRetryEventDO record = record(3);
        when(retryEventMapper.selectDue(32)).thenReturn(List.of(record));
        when(retryEventMapper.claim(eq(1L), anyString(), any(Date.class))).thenReturn(1);
        when(retryEventMapper.selectById(1L)).thenReturn(record);
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateCommandMessage.class)))
                .thenReturn(sendResult("broker-1"));
        when(retryEventMapper.deleteClaimed(eq(1L), anyString())).thenReturn(1);

        retryTaskService.scanAndRetry();

        verify(rocketMQTemplate).syncSend(
                eq(TicketOrderMessageConstants.CREATE_ORDER_TOPIC + ":" + TicketOrderMessageConstants.CREATE_ORDER_TAG),
                any(TicketOrderCreateCommandMessage.class));
        verify(retryEventMapper).deleteClaimed(eq(1L), anyString());
        verify(retryEventMapper, never()).releaseAfterFailure(
                any(), anyString(), anyString(), anyInt(), any(Date.class), anyString());
    }

    /**
     * MQ 发送失败且未达到上限时应释放租约并保留 RETRY 状态。
     */
    @Test
    void releasesTaskForAnotherRetryAfterTemporaryFailure() {
        CanalRelayRetryEventDO record = record(3);
        when(retryEventMapper.selectDue(32)).thenReturn(List.of(record));
        when(retryEventMapper.claim(eq(1L), anyString(), any(Date.class))).thenReturn(1);
        when(retryEventMapper.selectById(1L)).thenReturn(record);
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateCommandMessage.class)))
                .thenThrow(new IllegalStateException("broker timeout"));

        retryTaskService.scanAndRetry();

        verify(retryEventMapper).releaseAfterFailure(
                eq(1L), anyString(), eq("RETRY"), eq(4), any(Date.class), contains("IllegalStateException"));
    }

    /**
     * 达到最大尝试次数后应停止自动发送并转为 DEAD。
     */
    @Test
    void marksTaskDeadWhenRetryLimitIsReached() {
        CanalRelayRetryEventDO record = record(15);
        when(retryEventMapper.selectDue(32)).thenReturn(List.of(record));
        when(retryEventMapper.claim(eq(1L), anyString(), any(Date.class))).thenReturn(1);
        when(retryEventMapper.selectById(1L)).thenReturn(record);
        when(rocketMQTemplate.syncSend(anyString(), any(TicketOrderCreateCommandMessage.class)))
                .thenThrow(new IllegalStateException("broker timeout"));

        retryTaskService.scanAndRetry();

        verify(retryEventMapper).releaseAfterFailure(
                eq(1L), anyString(), eq("DEAD"), eq(16), any(Date.class), anyString());
    }

    /**
     * 多实例竞争同一候选时，条件抢占失败的实例不得发送消息。
     */
    @Test
    void skipsCandidateWhenLeaseWasClaimedByAnotherInstance() {
        CanalRelayRetryEventDO record = record(3);
        when(retryEventMapper.selectDue(32)).thenReturn(List.of(record));
        when(retryEventMapper.claim(eq(1L), anyString(), any(Date.class))).thenReturn(0);

        retryTaskService.scanAndRetry();

        verify(rocketMQTemplate, never()).syncSend(
                anyString(), any(TicketOrderCreateCommandMessage.class));
    }

    /**
     * 创建使用创建订单命名空间的加密重试记录。
     *
     * @param attempts 当前已累计发送次数
     * @return 测试数据库任务
     */
    private CanalRelayRetryEventDO record(int attempts) {
        return CanalRelayRetryEventDO.builder()
                .id(1L)
                .retryKey("ticket-order-creation:event-1")
                .namespace(TicketOrderMessageConstants.CREATE_ORDER_NAMESPACE)
                .eventId("event-1")
                .commandId("command-1")
                .aggregateId("reservation-1")
                .destinationTopic(TicketOrderMessageConstants.CREATE_ORDER_TOPIC)
                .destinationTag(TicketOrderMessageConstants.CREATE_ORDER_TAG)
                .delayLevel(0)
                .payloadCiphertext("ciphertext")
                .payloadKeyVersion(1)
                .status("RETRY")
                .attemptCount(attempts)
                .maxAttempts(16)
                .nextRetryTime(new Date())
                .build();
    }

    /**
     * 创建 RocketMQ 成功确认结果。
     *
     * @param messageId Broker 返回的消息标识
     * @return 可视为已确认的发送结果
     */
    private SendResult sendResult(String messageId) {
        SendResult result = new SendResult();
        result.setMsgId(messageId);
        return result;
    }
}
