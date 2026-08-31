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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCommandStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCommandMapper;
import org.opengoofy.index12306.biz.orderservice.service.impl.OrderCommandPersistenceServiceImpl;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.springframework.dao.DuplicateKeyException;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证订单创建 Command 的可靠接收、幂等校验和座位释放状态边界。
 */
@ExtendWith(MockitoExtension.class)
class OrderCommandPersistenceServiceTests {

    @Mock
    private OrderCommandMapper orderCommandMapper;

    private OrderCommandPersistenceService persistenceService;

    /**
     * 为每个用例创建只依赖 Mapper 的持久化服务。
     */
    @BeforeEach
    void setUp() {
        // 单元测试直接构造实现，事务边界由 Spring 注解在集成运行时提供。
        persistenceService = new OrderCommandPersistenceServiceImpl(orderCommandMapper);
    }

    /**
     * 验证首次接收会写入包含完整请求、重试次数和围栏初值的 RECEIVED 记录。
     */
    @Test
    void persistsRecoverableCommandBeforeBrokerAcknowledgement() {
        when(orderCommandMapper.selectOne(any())).thenReturn(null);
        when(orderCommandMapper.insert(any())).thenReturn(1);

        OrderCommandDO result = persistenceService.persistReceivedCommand(commandMessage());

        ArgumentCaptor<OrderCommandDO> commandCaptor = ArgumentCaptor.forClass(OrderCommandDO.class);
        verify(orderCommandMapper).insert(commandCaptor.capture());
        OrderCommandDO inserted = commandCaptor.getValue();
        assertThat(result).isSameAs(inserted);
        assertThat(inserted.getStatus()).isEqualTo(OrderCommandStatusEnum.RECEIVED.name());
        assertThat(inserted.getSourceMessageId()).isEqualTo("reservation-1");
        assertThat(inserted.getReservationId()).isEqualTo("reservation-1");
        assertThat(inserted.getUserId()).isEqualTo("1001");
        assertThat(inserted.getRecoveryShard()).isEqualTo(0);
        assertThat(inserted.getRequestFingerprint()).hasSize(64);
        assertThat(inserted.getRequestPayload()).contains("id-card-1", "13800000000");
        assertThat(inserted.getPayloadKeyVersion()).isEqualTo(1);
        assertThat(inserted.getAttemptCount()).isZero();
        assertThat(inserted.getMaxAttempts()).isEqualTo(16);
        assertThat(inserted.getNextRetryTime()).isNotNull();
        assertThat(inserted.getLeaseToken()).isZero();
    }

    /**
     * 验证并发插入由唯一键裁决后会读取并复用完全一致的原 Command。
     */
    @Test
    void reusesConcurrentCommandWhenImmutableFieldsMatch() {
        TicketOrderCreateCommandMessage message = commandMessage();
        when(orderCommandMapper.selectOne(any())).thenReturn(null);
        when(orderCommandMapper.insert(any())).thenReturn(1);
        OrderCommandDO persisted = persistenceService.persistReceivedCommand(message);

        reset(orderCommandMapper);
        when(orderCommandMapper.selectOne(any())).thenReturn(null, persisted);
        when(orderCommandMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));

        OrderCommandDO result = persistenceService.persistReceivedCommand(message);

        assertThat(result).isSameAs(persisted);
    }

    /**
     * 验证相同 commandId 不能覆盖已经绑定的另一份请求快照。
     */
    @Test
    void rejectsCommandIdBoundToDifferentPayload() {
        TicketOrderCreateCommandMessage message = commandMessage();
        OrderCommandDO conflicting = OrderCommandDO.builder()
                .commandId(message.getCommandId())
                .actionId(message.getActionId())
                .userId(message.getRequest().getUserId())
                .sourceMessageId(message.getMessageId())
                .reservationId(message.getReservationId())
                .messageVersion(message.getMessageVersion())
                .requestFingerprint("different-fingerprint")
                .status(OrderCommandStatusEnum.RECEIVED.name())
                .build();
        when(orderCommandMapper.selectOne(any())).thenReturn(conflicting);

        assertThatThrownBy(() -> persistenceService.persistReceivedCommand(message))
                .hasMessageContaining("Command 标识与原始请求不一致");
    }

    /**
     * 验证消息标识映射不一致时不会写入无法正确回传结果的任务。
     */
    @Test
    void rejectsInconsistentMessageIdentity() {
        TicketOrderCreateCommandMessage message = commandMessage();
        message.setMessageId("another-message");

        assertThatThrownBy(() -> persistenceService.persistReceivedCommand(message))
                .hasMessageContaining("消息标识关系不一致");
    }

    /**
     * 验证只有最终失败状态允许票务服务释放预留座位。
     */
    @Test
    void onlyFinalFailureAllowsSeatRelease() {
        assertThat(OrderCommandStatusEnum.FAILED.allowsSeatRelease()).isTrue();
        assertThat(OrderCommandStatusEnum.UNKNOWN.allowsSeatRelease()).isFalse();
        assertThat(OrderCommandStatusEnum.RETRY_WAIT.allowsSeatRelease()).isFalse();
        assertThat(OrderCommandStatusEnum.SUCCEEDED.allowsSeatRelease()).isFalse();
    }

    /**
     * 构造包含稳定标识和一张车票的可恢复订单创建消息。
     *
     * @return 测试用订单创建消息
     */
    private TicketOrderCreateCommandMessage commandMessage() {
        // 乘客敏感字段进入实体时仍为明文，实际写库由 ShardingSphere 对 request_payload 整列加密。
        TicketOrderCreateItem item = TicketOrderCreateItem.builder()
                .carriageNumber("01")
                .seatType(1)
                .seatNumber("01A")
                .realName("passenger-1")
                .idType(0)
                .idCard("id-card-1")
                .phone("13800000000")
                .amount(100)
                .ticketType(0)
                .build();
        TicketOrderCreateRequest request = TicketOrderCreateRequest.builder()
                .userId("1001")
                .username("alice")
                .trainId(2001L)
                .departure("A")
                .arrival("B")
                .source(0)
                .orderTime(new Date(1_000L))
                .ridingDate(new Date(2_000L))
                .trainNumber("G1")
                .departureTime(new Date(3_000L))
                .arrivalTime(new Date(4_000L))
                .ticketOrderItems(List.of(item))
                .build();
        return TicketOrderCreateCommandMessage.builder()
                .messageId("reservation-1")
                .messageVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .reservationId("reservation-1")
                .actionId("purchase-reservation-1")
                .commandId("purchase-reservation-1:create-order")
                .request(request)
                .build();
    }
}
