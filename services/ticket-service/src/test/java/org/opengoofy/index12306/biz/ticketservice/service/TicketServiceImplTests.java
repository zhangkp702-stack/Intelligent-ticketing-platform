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

package org.opengoofy.index12306.biz.ticketservice.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketChainMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationRelationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationRelationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.CarriageAvailabilityDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketPreviewRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.SelectSeatDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.refund.TrainRefundTicketParamNotNullChainFilter;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis.RedisSeatBitmapService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.TrainSeatTypeSelector;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.SeatSelectionStrategyRedisStore;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.SeatSelectionStrategySelector;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.SeatSelectionStrategySharedSelector;
import org.opengoofy.index12306.biz.ticketservice.service.impl.TicketServiceImpl;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.cache.core.CacheLoader;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainHandler;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证退票预览与真实退款共用的选票和金额计算边界。
 */
class TicketServiceImplTests {

    private final ThreadPoolExecutor selectSeatExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private TicketOrderRemoteService ticketOrderRemoteService;
    private TicketServiceImpl ticketService;

    /**
     * 创建仅包含退票预览所需依赖的票务服务。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // 预览流程只依赖订单远程服务和退款参数责任链，其余依赖不参与本用例。
        ticketOrderRemoteService = mock(TicketOrderRemoteService.class);
        PayRemoteService payRemoteService = mock(PayRemoteService.class);
        AbstractChainContext<RefundTicketReqDTO> refundChain = new AbstractChainContext<>();
        Map<String, List<AbstractChainHandler>> chainContainer =
                (Map<String, List<AbstractChainHandler>>) ReflectionTestUtils.getField(
                        refundChain, "abstractChainHandlerContainer");
        chainContainer.put(
                TicketChainMarkEnum.TRAIN_REFUND_TICKET_FILTER.name(),
                List.of(new TrainRefundTicketParamNotNullChainFilter()));
        ticketService = new TicketServiceImpl(
                null, null, null, null, ticketOrderRemoteService, payRemoteService, null, null, null, null,
                null, null, null, null, null, refundChain, null, null, null, null, null, null, null);
    }

    /**
     * 清理测试线程中的用户上下文，避免购票用例影响其他测试。
     */
    @AfterEach
    void tearDown() {
        // UserContext 使用线程本地变量，测试结束必须显式释放。
        UserContext.removeUser();
        // 单席别用例不会提交并行任务，仍关闭夹具线程池避免测试进程遗留资源。
        selectSeatExecutor.shutdownNow();
    }

    /**
     * 验证部分退票只汇总本次选中的已支付子订单，不使用整单乘客金额。
     */
    @Test
    void partialRefundPreviewOnlyCountsSelectedTicket() {
        TicketOrderPassengerDetailRespDTO selectedTicket = ticket("item-1", 1200);
        TicketOrderDetailRespDTO order = new TicketOrderDetailRespDTO();
        order.setOrderSn("order-1");
        order.setCanRefund(true);
        order.setPassengerDetails(List.of(selectedTicket, ticket("item-2", 5600)));
        when(ticketOrderRemoteService.querySelfTicketOrderByOrderSn("order-1"))
                .thenReturn(Results.success(order));
        when(ticketOrderRemoteService.queryTicketItemOrderById(any()))
                .thenReturn(Results.success(List.of(selectedTicket)));

        RefundTicketReqDTO request = new RefundTicketReqDTO();
        request.setOrderSn("order-1");
        request.setType(0);
        request.setSubOrderRecordIdReqList(List.of("item-1"));

        // 预览金额必须只等于被选中的一张车票金额。
        RefundTicketPreviewRespDTO result = ticketService.previewTicketRefund(request);
        assertThat(result.getRefundable()).isTrue();
        assertThat(result.getRefundAmount()).isEqualTo(1200);
        assertThat(result.getItems()).singleElement()
                .extracting("orderItemId", "refundableAmount")
                .containsExactly("item-1", 1200);
    }

    /**
     * 验证普通购票只同步提交 PREPARED 与建单事件，不在 HTTP 线程调用订单服务。
     */
    @Test
    @SuppressWarnings("unchecked")
    void acceptsPurchaseBeforeAsynchronousOrderCreationAndReleasesOptimisticHoldOnDatabaseConflict() {
        // 纯单元测试没有启动 MyBatis，先注册两个 LambdaQueryWrapper 使用的实体元数据。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                TrainStationPriceDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                TrainStationRelationDO.class);
        // 构造只覆盖选座、订单创建和 reservation 持久化窗口的依赖。
        TrainMapper trainMapper = mock(TrainMapper.class);
        TrainStationRelationMapper relationMapper = mock(TrainStationRelationMapper.class);
        DistributedCache distributedCache = mock(DistributedCache.class);
        TicketOrderRemoteService orderRemoteService = mock(TicketOrderRemoteService.class);
        TicketSeatReservationReleaseService reservationService = mock(TicketSeatReservationReleaseService.class);
        SeatService seatService = mock(SeatService.class);
        UserRemoteService userRemoteService = mock(UserRemoteService.class);
        TrainStationPriceMapper priceMapper = mock(TrainStationPriceMapper.class);
        AbstractStrategyChoose strategyChoose = mock(AbstractStrategyChoose.class);
        TrainStationService trainStationService = mock(TrainStationService.class);
        RedisSeatBitmapService redisSeatBitmapService = mock(RedisSeatBitmapService.class);
        TicketPurchaseMetrics purchaseMetrics = mock(TicketPurchaseMetrics.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        SeatSelectionStrategyRedisStore strategyRedisStore = mock(SeatSelectionStrategyRedisStore.class);
        TrainSeatTypeSelector seatTypeSelector = new TrainSeatTypeSelector(
                seatService, userRemoteService, priceMapper, strategyChoose, selectSeatExecutor, distributedCache,
                trainStationService, null, null, redisSeatBitmapService, purchaseMetrics,
                new SeatSelectionStrategySharedSelector(strategyRedisStore,
                        new SeatSelectionStrategySelector(), purchaseMetrics), null);
        ReflectionTestUtils.setField(seatTypeSelector, "redisSeatBitmapEnabled", true);
        ReflectionTestUtils.setField(seatTypeSelector, "ticketAvailabilityCacheUpdateType", "binlog");
        TicketServiceImpl purchaseService = spy(new TicketServiceImpl(
                trainMapper, relationMapper, null, distributedCache, orderRemoteService, null, null, null, null,
                reservationService, seatTypeSelector, null, null, null, null, null, null, null, null,
                redisSeatBitmapService, purchaseMetrics, null, transactionTemplate));
        doReturn(true).when(purchaseService).saveBatch(anyList());

        // 普通客户端请求不提供外部动作标识，但已经完成 Redis 占座并获得一个座位。
        Date ridingDate = new Date(1785283200000L);
        PurchaseTicketReqDTO request = new PurchaseTicketReqDTO();
        request.setTrainId("1");
        request.setDeparture("北京南");
        request.setArrival("上海虹桥");
        request.setDepartureDate(ridingDate);
        request.setServiceDate(ridingDate);
        PurchaseTicketPassengerDetailDTO passenger = new PurchaseTicketPassengerDetailDTO();
        passenger.setPassengerId("passenger-1");
        passenger.setSeatType(1);
        request.setPassengers(List.of(passenger));
        TrainDO train = new TrainDO();
        train.setTrainType(0);
        train.setTrainNumber("G1");
        TrainStationRelationDO relation = new TrainStationRelationDO();
        relation.setDepartureTime(ridingDate);
        relation.setArrivalTime(new Date(ridingDate.getTime() + TimeUnit.HOURS.toMillis(5)));
        TrainPurchaseTicketRespDTO selectedTicket = new TrainPurchaseTicketRespDTO();
        selectedTicket.setPassengerId("passenger-1");
        selectedTicket.setRealName("测试乘客");
        selectedTicket.setSeatType(1);
        selectedTicket.setCarriageNumber("01");
        selectedTicket.setSeatNumber("01A");
        selectedTicket.setAmount(55300);
        selectedTicket.setRedisSeatHoldId("reservation-1");
        PassengerRespDTO passengerDetail = new PassengerRespDTO();
        passengerDetail.setId("passenger-1");
        passengerDetail.setRealName("测试乘客");
        passengerDetail.setIdType(0);
        passengerDetail.setIdCard("110101199001010000");
        passengerDetail.setDiscountType(0);
        passengerDetail.setPhone("13800000000");
        TrainStationPriceDO price = new TrainStationPriceDO();
        price.setSeatType(1);
        price.setPrice(55300);
        UserContext.setUser(UserInfoDTO.builder().userId("1001").username("alice").build());

        // 本地事务先完成 PREPARED，远程订单返回成功后模拟订单绑定数据库失败。
        when(distributedCache.safeGet(anyString(), eq(TrainDO.class),
                any(CacheLoader.class), anyLong(), eq(TimeUnit.DAYS))).thenReturn(train);
        when(distributedCache.safeGet(anyString(), eq(String.class),
                any(CacheLoader.class), anyLong(), eq(TimeUnit.DAYS)))
                .thenReturn(JSON.toJSONString(List.of(price)));
        when(distributedCache.safeGet(anyString(), eq(TrainStationRelationDO.class),
                any(CacheLoader.class), anyLong(), eq(TimeUnit.DAYS))).thenReturn(relation);
        AtomicBoolean transactionActive = new AtomicBoolean(false);
        AtomicBoolean rejectDatabaseConfirm = new AtomicBoolean(false);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            transactionActive.set(true);
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } finally {
                transactionActive.set(false);
            }
        });
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(distributedCache.getInstance()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(seatService.listCandidateCarriages(eq("1"), eq(ridingDate), eq(1),
                eq("北京南"), eq("上海虹桥"), eq(1)))
                .thenReturn(List.of(new CarriageAvailabilityDTO("01", 100)));
        when(strategyChoose.<SelectSeatDTO, List<TrainPurchaseTicketRespDTO>>chooseAndExecuteResp(
                anyString(), any(SelectSeatDTO.class))).thenReturn(List.of(selectedTicket));
        when(redisSeatBitmapService.tryHold(eq("1"), eq(ridingDate), eq("北京南"), eq("上海虹桥"),
                eq(1), anyList(), anyString())).thenAnswer(invocation -> {
                    // Redis 临时占位必须早于 TransactionTemplate，避免网络往返占用数据库连接。
                    assertThat(transactionActive).isFalse();
                    String reservationId = invocation.getArgument(6);
                    selectedTicket.setRedisSeatHoldId(reservationId);
                    return reservationId;
                });
        when(seatService.tryLockSeat(eq("1"), eq(ridingDate), eq("北京南"), eq("上海虹桥"), anyList()))
                .thenAnswer(invocation -> {
                    // 数据库 CAS 确认必须与车票、reservation 和 Outbox 同处本地事务。
                    assertThat(transactionActive).isTrue();
                    return !rejectDatabaseConfirm.get();
                });
        when(userRemoteService.listPassengerQueryByIds(eq("alice"), eq(List.of("passenger-1"))))
                .thenReturn(Results.success(List.of(passengerDetail)));
        // 本地事务提交后立即返回受理标识和已选座位。
        var response = purchaseService.executePurchaseTickets(request);
        assertThat(response.getReservationId()).isNotBlank();
        assertThat(response.getOrderCreateStatus()).isEqualTo("PROCESSING");
        assertThat(response.getOrderSn()).isNull();
        assertThat(response.getTicketOrderDetails()).singleElement()
                .extracting("carriageNumber", "seatNumber")
                .containsExactly("01", "01A");
        ArgumentCaptor<TicketOrderCreateRemoteReqDTO> orderRequestCaptor =
                ArgumentCaptor.forClass(TicketOrderCreateRemoteReqDTO.class);
        verify(reservationService).prepareReservation(eq(response.getReservationId()), anyString(), anyString(),
                eq("1001"), eq("alice"), eq(1L), eq("北京南"), eq("上海虹桥"),
                eq(ridingDate), eq(ridingDate), anyList(), orderRequestCaptor.capture());
        String actionId = orderRequestCaptor.getValue().getActionId();
        String commandId = orderRequestCaptor.getValue().getCommandId();
        assertThat(actionId).startsWith("purchase-");
        assertThat(commandId).isEqualTo(actionId + ":create-order");
        assertThat(orderRequestCaptor.getValue().getTicketOrderItems()).singleElement()
                .extracting("carriageNumber", "seatNumber")
                .containsExactly("01", "01A");
        verify(orderRemoteService, never()).createTicketOrder(any());
        verify(reservationService, never()).bindOrder(anyString(), anyString());
        verify(redisSeatBitmapService, never()).releaseHeld(eq("1"), eq(ridingDate),
                eq("北京南"), eq("上海虹桥"), anyList());

        // Redis 已占位但数据库 CAS 冲突时，外层必须按当前 reservation 回收临时 owner。
        rejectDatabaseConfirm.set(true);
        assertThatThrownBy(() -> purchaseService.executePurchaseTickets(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("座位资源冲突");
        verify(redisSeatBitmapService).releaseHeld(eq("1"), eq(ridingDate),
                eq("北京南"), eq("上海虹桥"), anyList());
    }

    /**
     * 创建处于已支付状态的子订单明细。
     *
     * @param id 子订单记录标识
     * @param amount 车票金额
     * @return 可参与退款计算的子订单
     */
    private TicketOrderPassengerDetailRespDTO ticket(String id, int amount) {
        // 状态 10 与订单服务中的已支付子订单状态保持一致。
        return TicketOrderPassengerDetailRespDTO.builder()
                .id(id)
                .realName("测试乘客")
                .amount(amount)
                .status(10)
                .build();
    }
}
