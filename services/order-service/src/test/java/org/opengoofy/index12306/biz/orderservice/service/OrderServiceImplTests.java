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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderItemMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderMapper;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.mq.produce.DelayCloseOrderSendProduce;
import org.opengoofy.index12306.biz.orderservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.orderservice.service.impl.OrderServiceImpl;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 验证订单归属和可操作项计算的安全边界。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTests {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private OrderItemService orderItemService;

    @Mock
    private OrderPassengerRelationService orderPassengerRelationService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private DelayCloseOrderSendProduce delayCloseOrderSendProduce;

    @Mock
    private UserRemoteService userRemoteService;

    @InjectMocks
    private OrderServiceImpl orderService;

    /**
     * 清理测试线程中的用户上下文，避免影响后续用例。
     */
    @AfterEach
    void clearUserContext() {
        // 用户上下文基于线程本地变量，测试结束必须显式清理。
        UserContext.removeUser();
    }

    /**
     * 验证订单所有者可以读取详情，并由服务端计算退票可操作状态。
     */
    @Test
    void ownerCanReadOrderAndRefundCapability() {
        UserContext.setUser(UserInfoDTO.builder().userId("1001").username("alice").build());
        when(orderMapper.selectOne(any())).thenReturn(order(
                "1001", OrderStatusEnum.ALREADY_PAID.getStatus()));
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        // 已支付且尚未发车的本人订单应允许退票，但不允许取消或再次支付。
        TicketOrderDetailRespDTO result = orderService.querySelfTicketOrderByOrderSn("order-1");
        assertThat(result.getOrderSn()).isEqualTo("order-1");
        assertThat(result.getCanRefund()).isTrue();
        assertThat(result.getCanCancel()).isFalse();
        assertThat(result.getCanPay()).isFalse();
    }

    /**
     * 验证订单号正确但当前用户不匹配时仍拒绝访问。
     */
    @Test
    void otherUserCannotReadOrderByGuessingOrderSn() {
        UserContext.setUser(UserInfoDTO.builder().userId("2002").username("mallory").build());
        when(orderMapper.selectOne(any())).thenReturn(order(
                "1001", OrderStatusEnum.PENDING_PAYMENT.getStatus()));

        // 不向调用方区分订单不存在和订单属于其他用户，避免订单号枚举。
        assertThatThrownBy(() -> orderService.querySelfTicketOrderByOrderSn("order-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("订单不存在或无权访问");
    }

    /**
     * 创建包含未来乘车日期和发车时刻的订单实体。
     *
     * @param userId 订单用户标识
     * @param status 订单状态
     * @return 测试订单
     */
    private OrderDO order(String userId, int status) {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDateTime departure = LocalDate.now(zoneId).plusDays(1).atTime(9, 30);
        return OrderDO.builder()
                .orderSn("order-1")
                .userId(userId)
                .status(status)
                .ridingDate(Date.from(departure.toLocalDate().atStartOfDay(zoneId).toInstant()))
                .departureTime(Date.from(departure.atZone(zoneId).toInstant()))
                .build();
    }
}
