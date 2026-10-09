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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCdcOutboxEventDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCdcOutboxEventMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCommandMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderItemMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderMapper;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderSelfPageQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.OrderCommandStatusRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailSelfRespDTO;
import org.opengoofy.index12306.biz.orderservice.service.impl.OrderServiceImpl;
import org.opengoofy.index12306.biz.orderservice.service.monitor.OrderCreateMetrics;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.page.PageResponse;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证订单归属和可操作项计算的安全边界。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTests {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderCommandMapper orderCommandMapper;

    @Mock
    private OrderCdcOutboxEventMapper orderCdcOutboxEventMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private OrderItemService orderItemService;

    @Mock
    private OrderPassengerRelationService orderPassengerRelationService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private OrderCreateMetrics orderCreateMetrics;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private OrderServiceImpl orderService;

    /**
     * 默认模拟 CDC Outbox 追加成功，个别重复写入用例可覆盖该返回值。
     */
    @BeforeEach
    void appendCdcOutboxByDefault() {
        // 普通订单终态应首次追加事件，避免非 CDC 用例被默认零返回干扰。
        lenient().when(orderCdcOutboxEventMapper.insertIgnore(any())).thenReturn(1);
    }

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
     * 验证当前账号购买的订单即使乘车人是其他联系人，也会出现在可退订单列表中。
     */
    @Test
    void purchaserCanListRefundableOrderForAnotherPassenger() {
        UserContext.setUser(UserInfoDTO.builder().userId("1001").username("alice").build());
        OrderDO paidOrder = order("1001", OrderStatusEnum.ALREADY_PAID.getStatus());
        Page<OrderDO> orderPage = new Page<>(1, 20);
        orderPage.setRecords(List.of(paidOrder));
        orderPage.setTotal(1);
        when(orderMapper.selectPage(any(), any())).thenReturn(orderPage);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                OrderItemDO.builder()
                        .orderSn("order-1")
                        .realName("万重山")
                        .seatType(2)
                        .amount(55300)
                        .build()));

        // 订单归属使用购买账号，乘车人姓名只作为列表摘要，不能改变订单是否可见。
        PageResponse<TicketOrderDetailSelfRespDTO> result = orderService.pageSelfTicketOrder(
                new TicketOrderSelfPageQueryReqDTO());

        assertThat(result.getRecords()).singleElement().satisfies(order -> {
            assertThat(order.getOrderSn()).isEqualTo("order-1");
            assertThat(order.getRealName()).isEqualTo("万重山");
            assertThat(order.getCanRefund()).isTrue();
        });
    }

    /**
     * 验证命令查询只能返回当前用户的原订单结果。
     */
    @Test
    void commandStatusIsBoundToCurrentUser() {
        UserContext.setUser(UserInfoDTO.builder().userId("1001").username("alice").build());
        OrderDO existing = order("1001", OrderStatusEnum.PENDING_PAYMENT.getStatus());
        existing.setOrderSn("reservation-a");
        when(orderMapper.selectOne(any())).thenReturn(existing);

        // 权威查询只返回受理标识和订单号，不返回乘车人证件等订单正文。
        OrderCommandStatusRespDTO result = orderService.queryReservationOrderStatus("reservation-a");
        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getOrderSn()).isEqualTo("reservation-a");
        assertThat(result.getReservationId()).isEqualTo("reservation-a");
    }

    /**
     * 验证猜中其他用户命令标识也不能读取订单结果。
     */
    @Test
    void otherUserCannotQueryCommandResult() {
        UserContext.setUser(UserInfoDTO.builder().userId("2002").username("mallory").build());
        OrderDO existing = order("1001", OrderStatusEnum.PENDING_PAYMENT.getStatus());
        existing.setOrderSn("reservation-a");
        when(orderMapper.selectOne(any())).thenReturn(existing);

        // 即使底层查询意外返回其他分片记录，服务层仍执行最终归属校验。
        assertThatThrownBy(() -> orderService.queryReservationOrderStatus("reservation-a"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("无权访问");
    }

    /**
     * 订单主记录不存在但命令已独立持久化失败时，查询必须返回 FAILED 而不是误报 NOT_FOUND。
     */
    @Test
    void commandStatusReturnsPersistedFailureTerminal() {
        UserContext.setUser(UserInfoDTO.builder().userId("1001").username("alice").build());
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(orderCommandMapper.selectOne(any())).thenReturn(OrderCommandDO.builder()
                .userId("1001")
                .reservationId("reservation-a")
                .status("FAILED")
                .build());

        OrderCommandStatusRespDTO result = orderService.queryReservationOrderStatus("reservation-a");

        // 上游恢复器仅依据该持久化失败终态释放 PREPARED 座位。
        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getOrderSn()).isNull();
    }

    /**
     * 验证 Command 执行入口只是事务编排器，防止重新添加外层事务造成连接嵌套申请。
     */
    @Test
    void createOrderOrchestratorHasNoOuterTransaction() throws NoSuchMethodException {
        // 事务只能由内部 TransactionTemplate 顺序开启，入口方法本身不能持有数据库连接。
        Transactional annotation = OrderServiceImpl.class
                .getMethod("executeClaimedOrderCommand", OrderCommandDO.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation).isNull();
    }

    /**
     * 验证订单、Command 成功状态、延迟关单和结果 CDC Outbox 使用同一独立事务。
     */
    @Test
    void claimedCommandUsesOneOrderTransaction() {
        OrderCommandDO command = claimedCommand();
        OrderDO existing = order("1001", OrderStatusEnum.PENDING_PAYMENT.getStatus());
        existing.setOrderSn(command.getReservationId());
        existing.setRequestFingerprint(command.getRequestFingerprint());
        TransactionStatus orderTransaction = mock(TransactionStatus.class);

        // Command 已在 MQ 消费阶段提交，这里只需一笔订单事务完成全部本地事实。
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(orderTransaction);
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(orderCommandMapper.update(isNull(), any())).thenReturn(1);

        orderService.executeClaimedOrderCommand(command);

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues())
                .allSatisfy(definition -> assertThat(definition.getPropagationBehavior())
                        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> commandUpdateCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderCommandMapper).update(isNull(), commandUpdateCaptor.capture());
        assertThat(commandUpdateCaptor.getValue().getSqlSegment())
                .contains("status", "lease_owner", "lease_token", "reservation_id", "user_id");
        // 两类 CDC 事件先与订单事实落库，只有本地事务提交后 Canal 才能读取对应 Binlog。
        ArgumentCaptor<OrderCdcOutboxEventDO> outboxCaptor = ArgumentCaptor.forClass(OrderCdcOutboxEventDO.class);
        verify(orderCdcOutboxEventMapper, org.mockito.Mockito.times(2)).insertIgnore(outboxCaptor.capture());
        assertThat(outboxCaptor.getAllValues()).extracting(OrderCdcOutboxEventDO::getNamespace)
                .containsExactlyInAnyOrder(
                        TicketOrderMessageConstants.DELAY_CLOSE_ORDER_NAMESPACE,
                        TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE);
        assertThat(outboxCaptor.getAllValues()).filteredOn(each ->
                        TicketOrderMessageConstants.DELAY_CLOSE_ORDER_NAMESPACE.equals(each.getNamespace()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getAggregateId()).isEqualTo("order-1");
                    assertThat(event.getDelayLevel())
                            .isEqualTo(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_DELAY_LEVEL);
                });
        InOrder completionOrder = inOrder(orderCdcOutboxEventMapper, transactionManager);
        completionOrder.verify(orderCdcOutboxEventMapper, org.mockito.Mockito.times(2)).insertIgnore(any());
        completionOrder.verify(transactionManager).commit(orderTransaction);
    }

    /**
     * 验证请求载荷摘要损坏时不会创建订单，并在独立事务内形成明确 FAILED 结果。
     */
    @Test
    void invalidPayloadPersistsConfirmedFailureWithoutCreatingOrder() {
        OrderCommandDO command = claimedCommand();
        command.setRequestFingerprint("corrupted-fingerprint");
        TransactionStatus failureTransaction = mock(TransactionStatus.class);

        // 摘要校验发生在订单事务前，随后单独提交 FAILED 和失败结果 Outbox。
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(failureTransaction);
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(orderCommandMapper.update(isNull(), any())).thenReturn(1);

        orderService.executeClaimedOrderCommand(command);

        verify(orderMapper, never()).insert(any());
        verify(transactionManager).commit(failureTransaction);
        verify(orderCdcOutboxEventMapper).insertIgnore(any());
    }

    /**
     * 验证旧 fencing token 更新零行时订单事务整体回滚，迟到线程不能提交成功结果。
     */
    @Test
    void staleFencingTokenRollsBackOrderTransaction() {
        OrderCommandDO command = claimedCommand();
        OrderDO existing = order("1001", OrderStatusEnum.PENDING_PAYMENT.getStatus());
        existing.setOrderSn(command.getReservationId());
        existing.setRequestFingerprint(command.getRequestFingerprint());
        TransactionStatus orderTransaction = mock(TransactionStatus.class);
        TransactionStatus reconciliationTransaction = mock(TransactionStatus.class);

        // 两次围栏更新都返回零行，模拟租约已经由更高 token 的工作线程接管。
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(orderTransaction, reconciliationTransaction);
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(orderCommandMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> orderService.executeClaimedOrderCommand(command))
                .hasMessageContaining("执行围栏已失效");

        verify(transactionManager).rollback(orderTransaction);
        verify(transactionManager).rollback(reconciliationTransaction);
    }

    /**
     * 创建带稳定命令和一个乘车人的订单请求，供事务边界测试复用。
     *
     * @return 字段固定且可计算请求指纹的订单创建参数
     */
    private OrderCommandDO claimedCommand() {
        Date orderTime = new Date(1_700_000_000_000L);
        TicketOrderCreateItem item = TicketOrderCreateItem.builder()
                .carriageNumber("01")
                .seatNumber("01A")
                .seatType(2)
                .realName("Test User")
                .idType(0)
                .idCard("110101199001010000")
                .phone("13800000000")
                .amount(10000)
                .ticketType(0)
                .build();
        TicketOrderCreateRequest request = TicketOrderCreateRequest.builder()
                .userId("1001")
                .username("alice")
                .trainId(1001L)
                .departure("北京南")
                .arrival("上海虹桥")
                .source(0)
                .orderTime(orderTime)
                .ridingDate(orderTime)
                .trainNumber("G1001")
                .departureTime(orderTime)
                .arrivalTime(orderTime)
                .ticketOrderItems(List.of(item))
                .build();
        String requestPayload = JSON.toJSONString(request);
        // 指纹值在测试中调用与生产一致的 SHA-256 工具计算，保证载荷恢复路径完整执行。
        String fingerprint = org.apache.commons.codec.digest.DigestUtils.sha256Hex(requestPayload);
        return OrderCommandDO.builder()
                .userId("1001")
                .recoveryShard(9)
                .reservationId("reservation-a")
                .requestFingerprint(fingerprint)
                .requestPayload(requestPayload)
                .status("PROCESSING")
                .attemptCount(1)
                .maxAttempts(16)
                .leaseOwner("worker-a")
                .leaseToken(1L)
                .build();
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
