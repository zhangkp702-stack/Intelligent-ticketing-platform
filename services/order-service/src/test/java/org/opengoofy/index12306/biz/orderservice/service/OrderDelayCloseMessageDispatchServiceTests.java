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

package org.opengoofy.index12306.biz.orderservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCommandMapper;
import org.opengoofy.index12306.biz.orderservice.mq.produce.DelayCloseOrderSendProduce;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证延迟关单 Outbox 的认领、发送和失败重试边界。
 */
@ExtendWith(MockitoExtension.class)
class OrderDelayCloseMessageDispatchServiceTests {

    @Mock
    private OrderCommandMapper orderCommandMapper;

    @Mock
    private DelayCloseOrderSendProduce delayCloseOrderSendProduce;

    private OrderDelayCloseMessageDispatchService dispatchService;

    /**
     * 使用当前线程执行器构造服务，使异步投递在单元测试中可确定完成。
     */
    @BeforeEach
    void setUp() {
        // 单元测试没有启动 MyBatis，先注册 LambdaWrapper 所需的实体元数据。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                OrderCommandDO.class);
        // 生产环境使用有界线程池，测试改为直接执行以验证完整状态迁移。
        dispatchService = new OrderDelayCloseMessageDispatchService(
                orderCommandMapper, delayCloseOrderSendProduce, Runnable::run);
        ReflectionTestUtils.setField(dispatchService, "claimLeaseMillis", 15000L);
        ReflectionTestUtils.setField(dispatchService, "retryBaseMillis", 1000L);
        ReflectionTestUtils.setField(dispatchService, "retryMaxMillis", 60000L);
        ReflectionTestUtils.setField(dispatchService, "recoveryBatchSize", 1000);
    }

    /**
     * 验证即时任务只有成功认领 Outbox 后才发送，并在 MQ 确认后推进已发送状态。
     */
    @Test
    void dispatchesClaimedMessageAndMarksSent() {
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(orderCommandMapper.update(any(), any())).thenReturn(1);
        when(delayCloseOrderSendProduce.sendMessage(any())).thenReturn(sendResult);

        // 直接执行器会在方法返回前完成认领、MQ 发送和终态更新。
        dispatchService.dispatchAsync("1001", "action-1:create-order", "order-1");

        verify(delayCloseOrderSendProduce).sendMessage(any());
        verify(orderCommandMapper, times(2)).update(any(), any());
    }

    /**
     * 验证认领失败时不会向 RocketMQ 发送，避免多个订单实例重复投递。
     */
    @Test
    void skipsMessageWhenAnotherInstanceOwnsClaim() {
        when(orderCommandMapper.update(any(), any())).thenReturn(0);

        // 条件更新未命中代表其他实例已认领或消息已发送。
        dispatchService.dispatchAsync("1001", "action-1:create-order", "order-1");

        verify(delayCloseOrderSendProduce, never()).sendMessage(any());
        verify(orderCommandMapper).update(any(), any());
    }

    /**
     * 验证 MQ 异常不会逃逸到订单请求，并将 Outbox 重新置为可重试状态。
     */
    @Test
    void recordsRetryWhenRocketMqSendFails() {
        when(orderCommandMapper.update(any(), any())).thenReturn(1);
        when(delayCloseOrderSendProduce.sendMessage(any())).thenThrow(new RuntimeException("mq unavailable"));

        // 发送异常由后台任务吸收，并通过第二次数据库更新保存重试信息。
        dispatchService.dispatchAsync("1001", "action-1:create-order", "order-1");

        verify(orderCommandMapper, times(2)).update(any(), any());
    }

    /**
     * 验证恢复扫描会重新投递持久化的待发送记录。
     */
    @Test
    void recoveryScanDispatchesPersistedCandidate() {
        OrderCommandDO candidate = OrderCommandDO.builder()
                .userId("1001")
                .commandId("action-1:create-order")
                .orderSn("order-1")
                .delayCloseRetryCount(2)
                .build();
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(orderCommandMapper.selectList(any())).thenReturn(List.of(candidate));
        when(orderCommandMapper.update(any(), any())).thenReturn(1);
        when(delayCloseOrderSendProduce.sendMessage(any())).thenReturn(sendResult);

        // 低频扫描只负责重新入队，实际发送仍经过相同的认领逻辑。
        dispatchService.recoverPendingMessages();

        verify(delayCloseOrderSendProduce).sendMessage(any());
        verify(orderCommandMapper, times(2)).update(any(), any());
    }
}
