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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opengoofy.index12306.biz.orderservice.common.enums.DelayCloseMessageStatusEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCommandMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderItemMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderMapper;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderItemCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderSelfPageQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.OrderCommandStatusRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailSelfRespDTO;
import org.opengoofy.index12306.biz.orderservice.service.impl.OrderServiceImpl;
import org.opengoofy.index12306.biz.orderservice.service.monitor.OrderCreateMetrics;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.page.PageResponse;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    private OrderItemMapper orderItemMapper;

    @Mock
    private OrderItemService orderItemService;

    @Mock
    private OrderPassengerRelationService orderPassengerRelationService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private OrderDelayCloseMessageDispatchService orderDelayCloseMessageDispatchService;

    @Mock
    private OrderCreateMetrics orderCreateMetrics;

    @Mock
    private PlatformTransactionManager transactionManager;

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
        existing.setActionId("action-1");
        existing.setCommandId("action-1:create-order");
        when(orderMapper.selectOne(any())).thenReturn(existing);

        // 权威查询只返回命令、action 和订单号，不返回乘车人证件等订单正文。
        OrderCommandStatusRespDTO result = orderService.queryCommandStatus("action-1:create-order");
        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getOrderSn()).isEqualTo("order-1");
        assertThat(result.getActionId()).isEqualTo("action-1");
    }

    /**
     * 验证猜中其他用户命令标识也不能读取订单结果。
     */
    @Test
    void otherUserCannotQueryCommandResult() {
        UserContext.setUser(UserInfoDTO.builder().userId("2002").username("mallory").build());
        OrderDO existing = order("1001", OrderStatusEnum.PENDING_PAYMENT.getStatus());
        existing.setCommandId("action-1:create-order");
        when(orderMapper.selectOne(any())).thenReturn(existing);

        // 即使底层查询意外返回其他分片记录，服务层仍执行最终归属校验。
        assertThatThrownBy(() -> orderService.queryCommandStatus("action-1:create-order"))
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
                .commandId("action-1:create-order")
                .actionId("action-1")
                .userId("1001")
                .status("FAILED")
                .build());

        OrderCommandStatusRespDTO result = orderService.queryCommandStatus("action-1:create-order");

        // 上游恢复器仅依据该持久化失败终态释放 PREPARED 座位。
        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getOrderSn()).isNull();
    }

    /**
     * 验证订单创建入口只是事务编排器，防止重新添加外层事务造成连接嵌套申请。
     */
    @Test
    void createOrderOrchestratorHasNoOuterTransaction() throws NoSuchMethodException {
        // 事务只能由内部 TransactionTemplate 顺序开启，入口方法本身不能持有数据库连接。
        Transactional annotation = OrderServiceImpl.class
                .getMethod("createTicketOrder", TicketOrderCreateReqDTO.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation).isNull();
    }

    /**
     * 验证稳定命令登记和订单落库使用两个顺序提交的独立事务，不再嵌套占用连接。
     */
    @Test
    void createOrderUsesSequentialRequiresNewTransactions() {
        TicketOrderCreateReqDTO request = createOrderRequest();
        String requestFingerprint = ReflectionTestUtils.invokeMethod(
                orderService, "createRequestFingerprint", request);
        OrderDO existing = order("1001", OrderStatusEnum.PENDING_PAYMENT.getStatus());
        existing.setActionId(request.getActionId());
        existing.setCommandId(request.getCommandId());
        existing.setRequestFingerprint(requestFingerprint);
        TransactionStatus commandTransaction = mock(TransactionStatus.class);
        TransactionStatus orderTransaction = mock(TransactionStatus.class);

        // 第一笔事务登记 PROCESSING，第二笔事务复用已落库订单并推进命令成功。
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(commandTransaction, orderTransaction);
        when(orderCommandMapper.selectOne(any())).thenReturn(null);
        when(orderCommandMapper.insert(any())).thenReturn(1);
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(orderCommandMapper.update(any(), any())).thenReturn(1);

        String orderSn = orderService.createTicketOrder(request);

        assertThat(orderSn).isEqualTo("order-1");
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues())
                .allSatisfy(definition -> assertThat(definition.getPropagationBehavior())
                        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        InOrder transactionOrder = inOrder(transactionManager);
        transactionOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        transactionOrder.verify(transactionManager).commit(commandTransaction);
        transactionOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        transactionOrder.verify(transactionManager).commit(orderTransaction);
        ArgumentCaptor<OrderCommandDO> commandUpdate = ArgumentCaptor.forClass(OrderCommandDO.class);
        verify(orderCommandMapper).update(commandUpdate.capture(), any());
        assertThat(commandUpdate.getValue().getDelayCloseStatus())
                .isEqualTo(DelayCloseMessageStatusEnum.PENDING.getStatus());
        assertThat(commandUpdate.getValue().getDelayCloseNextRetryTime()).isNotNull();
        // MQ 网络发送只允许在订单事务提交后进入后台投递器。
        InOrder completionOrder = inOrder(transactionManager, orderDelayCloseMessageDispatchService);
        completionOrder.verify(transactionManager).commit(orderTransaction);
        completionOrder.verify(orderDelayCloseMessageDispatchService)
                .dispatchAsync("1001", "action-1:create-order", "order-1");
    }

    /**
     * 验证订单事务失败回滚后，失败命令使用第三笔独立事务提交为可恢复终态。
     */
    @Test
    void createOrderPersistsFailureAfterOrderTransactionRollback() {
        TicketOrderCreateReqDTO request = createOrderRequest();
        OrderDO conflictingOrder = order("1001", OrderStatusEnum.PENDING_PAYMENT.getStatus());
        conflictingOrder.setActionId(request.getActionId());
        conflictingOrder.setCommandId(request.getCommandId());
        conflictingOrder.setRequestFingerprint("different-fingerprint");
        TransactionStatus commandTransaction = mock(TransactionStatus.class);
        TransactionStatus orderTransaction = mock(TransactionStatus.class);
        TransactionStatus failureTransaction = mock(TransactionStatus.class);

        // 主订单事务校验失败后先回滚，再单独提交 FAILED，不能把失败事实一起回滚。
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(commandTransaction, orderTransaction, failureTransaction);
        when(orderCommandMapper.selectOne(any())).thenReturn(null);
        when(orderCommandMapper.insert(any())).thenReturn(1);
        when(orderMapper.selectOne(any())).thenReturn(conflictingOrder);

        assertThatThrownBy(() -> orderService.createTicketOrder(request))
                .hasMessageContaining("订单命令标识与原请求不一致");

        InOrder transactionOrder = inOrder(transactionManager);
        transactionOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        transactionOrder.verify(transactionManager).commit(commandTransaction);
        transactionOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        transactionOrder.verify(transactionManager).rollback(orderTransaction);
        transactionOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        transactionOrder.verify(transactionManager).commit(failureTransaction);
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(3)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues())
                .allSatisfy(definition -> assertThat(definition.getPropagationBehavior())
                        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    /**
     * 创建带稳定命令和一个乘车人的订单请求，供事务边界测试复用。
     *
     * @return 字段固定且可计算请求指纹的订单创建参数
     */
    private TicketOrderCreateReqDTO createOrderRequest() {
        Date orderTime = new Date(1_700_000_000_000L);
        TicketOrderItemCreateReqDTO item = new TicketOrderItemCreateReqDTO();
        item.setPassengerId("passenger-1");
        item.setCarriageNumber("01");
        item.setSeatNumber("01A");
        item.setSeatType(2);
        item.setRealName("Test User");
        item.setIdType(0);
        item.setIdCard("110101199001010000");
        item.setPhone("13800000000");
        item.setAmount(10000);
        item.setTicketType(0);

        // 固定全部指纹字段，确保重复订单校验只受测试指定的摘要影响。
        TicketOrderCreateReqDTO request = new TicketOrderCreateReqDTO();
        request.setActionId("action-1");
        request.setCommandId("action-1:create-order");
        request.setUserId(1001L);
        request.setUsername("alice");
        request.setTrainId(1001L);
        request.setDeparture("北京南");
        request.setArrival("上海虹桥");
        request.setSource(0);
        request.setOrderTime(orderTime);
        request.setRidingDate(orderTime);
        request.setTrainNumber("G1001");
        request.setDepartureTime(orderTime);
        request.setArrivalTime(orderTime);
        request.setTicketOrderItems(List.of(item));
        return request;
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
