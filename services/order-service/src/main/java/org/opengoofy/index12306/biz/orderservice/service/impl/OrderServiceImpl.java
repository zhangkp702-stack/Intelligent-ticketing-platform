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

package org.opengoofy.index12306.biz.orderservice.service.impl;

import cn.crane4j.annotation.AutoOperate;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.text.StrBuilder;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCanalErrorCodeEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderItemStatusEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemPassengerDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderItemMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderMapper;
import org.opengoofy.index12306.biz.orderservice.dto.domain.OrderStatusReversalDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.BalancePaymentConfirmReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderItemCreateReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderPageQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderSelfPageQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailSelfRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.mq.event.DelayCloseOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.event.PayResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.produce.DelayCloseOrderSendProduce;
import org.opengoofy.index12306.biz.orderservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.orderservice.remote.dto.UserQueryActualRespDTO;
import org.opengoofy.index12306.biz.orderservice.service.OrderItemService;
import org.opengoofy.index12306.biz.orderservice.service.OrderPassengerRelationService;
import org.opengoofy.index12306.biz.orderservice.service.OrderService;
import org.opengoofy.index12306.biz.orderservice.service.orderid.OrderIdGeneratorManager;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.page.PageResponse;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.database.toolkit.PageUtil;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 订单服务接口层实现
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderItemService orderItemService;
    private final OrderPassengerRelationService orderPassengerRelationService;
    private final RedissonClient redissonClient;
    private final DelayCloseOrderSendProduce delayCloseOrderSendProduce;
    private final UserRemoteService userRemoteService;

    /**
     * 根据订单号查询内部订单详情，不执行终端用户归属校验。
     *
     * @param orderSn 订单号
     * @return 订单详情
     */
    @Override
    public TicketOrderDetailRespDTO queryTicketOrderByOrderSn(String orderSn) {
        // 内部调用仍保留原接口，但统一处理订单不存在，避免空实体转换产生异常。
        OrderDO orderDO = requireOrder(orderSn);
        return buildOrderDetail(orderDO);
    }

    /**
     * 查询当前登录用户自己的订单详情并返回可操作状态。
     *
     * @param orderSn 订单号
     * @return 经过归属校验的订单详情
     */
    @Override
    public TicketOrderDetailRespDTO querySelfTicketOrderByOrderSn(String orderSn) {
        // 订单号不能作为授权凭据，必须同时匹配网关注入的当前用户标识。
        OrderDO orderDO = requireOrder(orderSn);
        verifyOrderOwner(orderDO);
        return buildOrderDetail(orderDO);
    }

    /**
     * 幂等确认当前用户订单已经完成站内余额支付。
     *
     * @param requestParam 订单号、支付渠道和支付时间
     * @return 首次确认或重复确认均返回 true
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmBalancePayment(BalancePaymentConfirmReqDTO requestParam) {
        // 订单服务再次校验订单归属，内部确认接口不能只依赖支付服务传入的订单号。
        OrderDO orderDO = requireOrder(requestParam.getOrderSn());
        verifyOrderOwner(orderDO);
        if (Objects.equals(orderDO.getStatus(), OrderStatusEnum.ALREADY_PAID.getStatus())) {
            return true;
        }
        if (!Objects.equals(orderDO.getStatus(), OrderStatusEnum.PENDING_PAYMENT.getStatus())) {
            throw new ClientException("当前订单状态不允许确认支付");
        }

        // 仅允许待支付订单原子变更为已支付，避免取消和支付并发覆盖状态。
        OrderDO updateOrder = new OrderDO();
        updateOrder.setStatus(OrderStatusEnum.ALREADY_PAID.getStatus());
        updateOrder.setPayType(requestParam.getChannel());
        updateOrder.setPayTime(requestParam.getPayTime());
        int orderUpdated = orderMapper.update(
                updateOrder,
                Wrappers.lambdaUpdate(OrderDO.class)
                        .eq(OrderDO::getOrderSn, requestParam.getOrderSn())
                        .eq(OrderDO::getStatus, OrderStatusEnum.PENDING_PAYMENT.getStatus()));
        if (orderUpdated != 1) {
            throw new ServiceException("确认订单支付状态失败");
        }

        // 主订单确认后同步更新全部乘车人子订单，保持订单展示和退票判断一致。
        OrderItemDO updateOrderItem = new OrderItemDO();
        updateOrderItem.setStatus(OrderItemStatusEnum.ALREADY_PAID.getStatus());
        int itemUpdated = orderItemMapper.update(
                updateOrderItem,
                Wrappers.lambdaUpdate(OrderItemDO.class)
                        .eq(OrderItemDO::getOrderSn, requestParam.getOrderSn())
                        .eq(OrderItemDO::getStatus, OrderItemStatusEnum.PENDING_PAYMENT.getStatus()));
        if (itemUpdated <= 0) {
            throw new ServiceException("确认车票支付状态失败");
        }
        return true;
    }

    /**
     * 把订单实体和子订单明细转换为统一订单详情。
     *
     * @param orderDO 订单实体
     * @return 包含可操作标记的订单详情
     */
    private TicketOrderDetailRespDTO buildOrderDetail(OrderDO orderDO) {
        TicketOrderDetailRespDTO result = BeanUtil.convert(orderDO, TicketOrderDetailRespDTO.class);
        LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderDO.getOrderSn());
        List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(orderItemQueryWrapper);
        result.setPassengerDetails(BeanUtil.convert(orderItemDOList, TicketOrderPassengerDetailRespDTO.class));

        // 可操作标记只依据持久化订单状态和发车时间计算，模型不能自行推断。
        boolean beforeDeparture = isBeforeDeparture(orderDO);
        result.setCanCancel(Objects.equals(orderDO.getStatus(), OrderStatusEnum.PENDING_PAYMENT.getStatus()));
        result.setCanPay(Objects.equals(orderDO.getStatus(), OrderStatusEnum.PENDING_PAYMENT.getStatus()));
        result.setCanRefund(beforeDeparture
                && (Objects.equals(orderDO.getStatus(), OrderStatusEnum.ALREADY_PAID.getStatus())
                || Objects.equals(orderDO.getStatus(), OrderStatusEnum.PARTIAL_REFUND.getStatus())));
        return result;
    }

    @AutoOperate(type = TicketOrderDetailRespDTO.class, on = "data.records")
    @Override
    public PageResponse<TicketOrderDetailRespDTO> pageTicketOrder(TicketOrderPageQueryReqDTO requestParam) {
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getUserId, requestParam.getUserId())
                .in(OrderDO::getStatus, buildOrderStatusList(requestParam))
                .orderByDesc(OrderDO::getOrderTime);
        IPage<OrderDO> orderPage = orderMapper.selectPage(PageUtil.convert(requestParam), queryWrapper);
        return PageUtil.convert(orderPage, each -> {
            TicketOrderDetailRespDTO result = BeanUtil.convert(each, TicketOrderDetailRespDTO.class);
            LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, each.getOrderSn());
            List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(orderItemQueryWrapper);
            result.setPassengerDetails(BeanUtil.convert(orderItemDOList, TicketOrderPassengerDetailRespDTO.class));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public String createTicketOrder(TicketOrderCreateReqDTO requestParam) {
        // 通过基因法将用户 ID 融入到订单号
        String orderSn = OrderIdGeneratorManager.generateId(requestParam.getUserId());
        // 创建汇总订单，存储整个账单的公共情况
        OrderDO orderDO = OrderDO.builder().orderSn(orderSn)
                .orderTime(requestParam.getOrderTime())
                .departure(requestParam.getDeparture())
                .departureTime(requestParam.getDepartureTime())
                .ridingDate(requestParam.getRidingDate())
                .arrivalTime(requestParam.getArrivalTime())
                .trainNumber(requestParam.getTrainNumber())
                .arrival(requestParam.getArrival())
                .trainId(requestParam.getTrainId())
                .source(requestParam.getSource())
                .status(OrderStatusEnum.PENDING_PAYMENT.getStatus())
                .username(requestParam.getUsername())
                .userId(String.valueOf(requestParam.getUserId()))
                .build();
        orderMapper.insert(orderDO);
        List<TicketOrderItemCreateReqDTO> ticketOrderItems = requestParam.getTicketOrderItems();
        List<OrderItemDO> orderItemDOList = new ArrayList<>();
        List<OrderItemPassengerDO> orderPassengerRelationDOList = new ArrayList<>();
        ticketOrderItems.forEach(each -> {
            OrderItemDO orderItemDO = OrderItemDO.builder()
                    .trainId(requestParam.getTrainId())
                    .seatNumber(each.getSeatNumber())
                    .carriageNumber(each.getCarriageNumber())
                    .realName(each.getRealName())
                    .orderSn(orderSn)
                    .phone(each.getPhone())
                    .seatType(each.getSeatType())
                    .username(requestParam.getUsername()).amount(each.getAmount()).carriageNumber(each.getCarriageNumber())
                    .idCard(each.getIdCard())
                    .ticketType(each.getTicketType())
                    .idType(each.getIdType())
                    .userId(String.valueOf(requestParam.getUserId()))
                    .status(0)
                    .build();
            orderItemDOList.add(orderItemDO);
            OrderItemPassengerDO orderPassengerRelationDO = OrderItemPassengerDO.builder()
                    .idType(each.getIdType())
                    .idCard(each.getIdCard())
                    .orderSn(orderSn)
                    .build();
            orderPassengerRelationDOList.add(orderPassengerRelationDO);
        });
        orderItemService.saveBatch(orderItemDOList);
        orderPassengerRelationService.saveBatch(orderPassengerRelationDOList);
        try {
            // 发送 RocketMQ 延时消息，指定时间后取消订单
            DelayCloseOrderEvent delayCloseOrderEvent = DelayCloseOrderEvent.builder()
                    .orderSn(orderSn)
                    .build();
            // 创建订单并支付后延时关闭订单消息怎么办？详情查看：https://nageoffer.com/12306/question
            SendResult sendResult = delayCloseOrderSendProduce.sendMessage(delayCloseOrderEvent);
            if (!Objects.equals(sendResult.getSendStatus(), SendStatus.SEND_OK)) {
                throw new ServiceException("投递延迟关闭订单消息队列失败");
            }
        } catch (Throwable ex) {
            log.error("延迟关闭订单消息队列发送错误，请求参数：{}", JSON.toJSONString(requestParam), ex);
            throw ex;
        }
        return orderSn;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean closeTickOrder(CancelTicketOrderReqDTO requestParam) {
        String orderSn = requestParam.getOrderSn();
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn)
                .select(OrderDO::getStatus);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (Objects.isNull(orderDO) || orderDO.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getStatus()) {
            return false;
        }
        // 延迟关单属于内部任务，不依赖终端用户上下文，但仍复用相同原子状态更新。
        return cancelTickOrderInternal(orderSn, false);
    }

    /**
     * 取消当前登录用户自己的待支付订单，重复取消已关闭订单时幂等返回成功。
     *
     * @param requestParam 取消订单请求
     * @return 订单已经或本次成功进入关闭状态时返回 true
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean cancelTickOrder(CancelTicketOrderReqDTO requestParam) {
        // 外部取消入口必须校验订单归属，内部延迟关单不会调用此分支。
        return cancelTickOrderInternal(requestParam.getOrderSn(), true);
    }

    /**
     * 在订单维度分布式锁内完成关闭状态和子订单状态更新。
     *
     * @param orderSn 订单号
     * @param verifyOwner 是否校验当前登录用户为订单所有者
     * @return 订单已经或本次成功关闭时返回 true
     */
    private boolean cancelTickOrderInternal(String orderSn, boolean verifyOwner) {
        RLock lock = redissonClient.getLock(StrBuilder.create("order:canal:order_sn_").append(orderSn).toString());
        if (!lock.tryLock()) {
            throw new ClientException(OrderCanalErrorCodeEnum.ORDER_CANAL_REPETITION_ERROR);
        }
        try {
            // 获取锁后重新读取状态，保证并发取消不会同时通过状态判断。
            OrderDO orderDO = requireOrder(orderSn);
            if (verifyOwner) {
                verifyOrderOwner(orderDO);
            }
            if (Objects.equals(orderDO.getStatus(), OrderStatusEnum.CLOSED.getStatus())) {
                return true;
            }
            if (!Objects.equals(orderDO.getStatus(), OrderStatusEnum.PENDING_PAYMENT.getStatus())) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
            }
            OrderDO updateOrderDO = new OrderDO();
            updateOrderDO.setStatus(OrderStatusEnum.CLOSED.getStatus());
            LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                    .eq(OrderDO::getOrderSn, orderSn)
                    .eq(OrderDO::getStatus, OrderStatusEnum.PENDING_PAYMENT.getStatus());
            int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
            if (updateResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_ERROR);
            }
            OrderItemDO updateOrderItemDO = new OrderItemDO();
            updateOrderItemDO.setStatus(OrderItemStatusEnum.CLOSED.getStatus());
            LambdaUpdateWrapper<OrderItemDO> updateItemWrapper = Wrappers.lambdaUpdate(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, orderSn);
            int updateItemResult = orderItemMapper.update(updateOrderItemDO, updateItemWrapper);
            if (updateItemResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_ERROR);
            }
        } finally {
            lock.unlock();
        }
        return true;
    }

    @Override
    public void statusReversal(OrderStatusReversalDTO requestParam) {
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (orderDO == null) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_UNKNOWN_ERROR);
        } else if (orderDO.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getStatus()) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
        }
        RLock lock = redissonClient.getLock(StrBuilder.create("order:status-reversal:order_sn_").append(requestParam.getOrderSn()).toString());
        if (!lock.tryLock()) {
            log.warn("订单重复修改状态，状态反转请求参数：{}", JSON.toJSONString(requestParam));
        }
        try {
            OrderDO updateOrderDO = new OrderDO();
            updateOrderDO.setStatus(requestParam.getOrderStatus());
            LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                    .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
            int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
            if (updateResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
            }
            OrderItemDO orderItemDO = new OrderItemDO();
            orderItemDO.setStatus(requestParam.getOrderItemStatus());
            LambdaUpdateWrapper<OrderItemDO> orderItemUpdateWrapper = Wrappers.lambdaUpdate(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, requestParam.getOrderSn());
            int orderItemUpdateResult = orderItemMapper.update(orderItemDO, orderItemUpdateWrapper);
            if (orderItemUpdateResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void payCallbackOrder(PayResultCallbackOrderEvent requestParam) {
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setPayTime(requestParam.getGmtPayment());
        updateOrderDO.setPayType(requestParam.getChannel());
        LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
        int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
        if (updateResult <= 0) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
        }
    }

    @Override
    public PageResponse<TicketOrderDetailSelfRespDTO> pageSelfTicketOrder(TicketOrderSelfPageQueryReqDTO requestParam) {
        Result<UserQueryActualRespDTO> userActualResp = userRemoteService.queryActualUserByUsername(UserContext.getUsername());
        LambdaQueryWrapper<OrderItemPassengerDO> queryWrapper = Wrappers.lambdaQuery(OrderItemPassengerDO.class)
                .eq(OrderItemPassengerDO::getIdCard, userActualResp.getData().getIdCard())
                .orderByDesc(OrderItemPassengerDO::getCreateTime);
        IPage<OrderItemPassengerDO> orderItemPassengerPage = orderPassengerRelationService.page(PageUtil.convert(requestParam), queryWrapper);
        return PageUtil.convert(orderItemPassengerPage, each -> {
            LambdaQueryWrapper<OrderDO> orderQueryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                    .eq(OrderDO::getOrderSn, each.getOrderSn());
            OrderDO orderDO = orderMapper.selectOne(orderQueryWrapper);
            LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, each.getOrderSn())
                    .eq(OrderItemDO::getIdCard, each.getIdCard());
            OrderItemDO orderItemDO = orderItemMapper.selectOne(orderItemQueryWrapper);
            TicketOrderDetailSelfRespDTO actualResult = BeanUtil.convert(orderDO, TicketOrderDetailSelfRespDTO.class);
            BeanUtil.convertIgnoreNullAndBlank(orderItemDO, actualResult);
            // 本人订单列表补充订单号、状态和服务端计算的可操作项，供后续安全详情查询使用。
            boolean beforeDeparture = isBeforeDeparture(orderDO);
            actualResult.setCanCancel(Objects.equals(orderDO.getStatus(), OrderStatusEnum.PENDING_PAYMENT.getStatus()));
            actualResult.setCanPay(Objects.equals(orderDO.getStatus(), OrderStatusEnum.PENDING_PAYMENT.getStatus()));
            actualResult.setCanRefund(beforeDeparture
                    && (Objects.equals(orderDO.getStatus(), OrderStatusEnum.ALREADY_PAID.getStatus())
                    || Objects.equals(orderDO.getStatus(), OrderStatusEnum.PARTIAL_REFUND.getStatus())));
            return actualResult;
        });
    }

    /**
     * 根据订单号读取必须存在的订单实体。
     *
     * @param orderSn 订单号
     * @return 订单实体
     */
    private OrderDO requireOrder(String orderSn) {
        // 所有订单详情、取消和归属校验都复用同一不存在语义。
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (orderDO == null) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_UNKNOWN_ERROR);
        }
        return orderDO;
    }

    /**
     * 校验订单属于网关注入的当前登录用户。
     *
     * @param orderDO 待访问订单
     */
    private void verifyOrderOwner(OrderDO orderDO) {
        // 用户上下文缺失和用户不匹配使用同一安全提示，避免通过订单号探测他人订单。
        if (UserContext.getUserId() == null || !UserContext.getUserId().equals(orderDO.getUserId())) {
            throw new ClientException("订单不存在或无权访问");
        }
    }

    /**
     * 根据乘车日期和发车时刻判断列车是否尚未发车。
     *
     * @param orderDO 订单实体
     * @return 当前时间早于订单实际发车时间时返回 true
     */
    private boolean isBeforeDeparture(OrderDO orderDO) {
        if (orderDO.getRidingDate() == null || orderDO.getDepartureTime() == null) {
            return false;
        }

        // 订单将乘车日期和每日发车时刻分开保存，需要组合后再判断退票截止边界。
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate ridingDate = java.time.Instant.ofEpochMilli(orderDO.getRidingDate().getTime())
                .atZone(zoneId).toLocalDate();
        LocalTime departureTime = java.time.Instant.ofEpochMilli(orderDO.getDepartureTime().getTime())
                .atZone(zoneId).toLocalTime();
        return LocalDateTime.of(ridingDate, departureTime).isAfter(LocalDateTime.now(zoneId));
    }

    private List<Integer> buildOrderStatusList(TicketOrderPageQueryReqDTO requestParam) {
        List<Integer> result = new ArrayList<>();
        switch (requestParam.getStatusType()) {
            case 0 -> result = ListUtil.of(
                    OrderStatusEnum.PENDING_PAYMENT.getStatus()
            );
            case 1 -> result = ListUtil.of(
                    OrderStatusEnum.ALREADY_PAID.getStatus(),
                    OrderStatusEnum.PARTIAL_REFUND.getStatus(),
                    OrderStatusEnum.FULL_REFUND.getStatus()
            );
            case 2 -> result = ListUtil.of(
                    OrderStatusEnum.COMPLETED.getStatus()
            );
        }
        return result;
    }
}
