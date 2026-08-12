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
import org.opengoofy.index12306.biz.orderservice.dto.resp.OrderCommandStatusRespDTO;
import org.opengoofy.index12306.biz.orderservice.mq.event.DelayCloseOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.event.PayResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.produce.DelayCloseOrderSendProduce;
import org.opengoofy.index12306.biz.orderservice.service.OrderItemService;
import org.opengoofy.index12306.biz.orderservice.service.OrderPassengerRelationService;
import org.opengoofy.index12306.biz.orderservice.service.OrderService;
import org.opengoofy.index12306.biz.orderservice.service.orderid.OrderIdGeneratorManager;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.page.PageResponse;
import org.opengoofy.index12306.framework.starter.database.toolkit.PageUtil;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
    private final DelayCloseOrderSendProduce delayCloseOrderSendProduce;

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
        String commandId = normalizeCreateCommand(requestParam);
        String requestFingerprint = commandId == null ? null : createRequestFingerprint(requestParam);
        if (commandId != null) {
            // 网络重试优先读取同一用户分片内的原订单，避免再次生成订单号和子订单。
            OrderDO existing = findByCommandId(commandId, String.valueOf(requestParam.getUserId()));
            if (existing != null) {
                return resolveExistingCreateCommand(requestParam, requestFingerprint, existing);
            }
        }
        // 通过基因法将用户 ID 融入到订单号
        String orderSn = OrderIdGeneratorManager.generateId(requestParam.getUserId());
        // 创建汇总订单，存储整个账单的公共情况
        OrderDO orderDO = OrderDO.builder()
                .actionId(requestParam.getActionId())
                .commandId(commandId)
                .requestFingerprint(requestFingerprint)
                .orderSn(orderSn)
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
        try {
            // 每个订单分片上的命令唯一约束是并发创建的最终防线。
            orderMapper.insert(orderDO);
        } catch (DuplicateKeyException exception) {
            OrderDO existing = findByCommandId(commandId, String.valueOf(requestParam.getUserId()));
            if (existing == null) {
                throw exception;
            }
            return resolveExistingCreateCommand(requestParam, requestFingerprint, existing);
        }
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

    /**
     * 查询当前用户订单创建命令的权威状态。
     *
     * @param commandId 稳定命令标识
     * @return 未找到或已成功创建的安全结果
     */
    @Override
    public OrderCommandStatusRespDTO queryCommandStatus(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new ServiceException("订单命令标识不能为空");
        }
        String normalized = commandId.trim();
        String userId = UserContext.getUserId();
        OrderDO order = findByCommandId(normalized, userId);
        if (order == null) {
            return OrderCommandStatusRespDTO.builder()
                    .commandId(normalized)
                    .status("NOT_FOUND")
                    .build();
        }
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new ClientException("订单命令不存在或无权访问");
        }
        // 查询始终带用户分片键，其他用户即使猜中 commandId 也无法读取订单号。
        return OrderCommandStatusRespDTO.builder()
                .commandId(order.getCommandId())
                .actionId(order.getActionId())
                .status("SUCCEEDED")
                .orderSn(order.getOrderSn())
                .build();
    }

    /**
     * 校验并规范化 Agent 订单创建命令。
     *
     * @param requestParam 订单创建参数
     * @return 普通请求返回 null，Agent 请求返回规范化命令标识
     */
    private String normalizeCreateCommand(TicketOrderCreateReqDTO requestParam) {
        boolean hasAction = requestParam.getActionId() != null && !requestParam.getActionId().isBlank();
        boolean hasCommand = requestParam.getCommandId() != null && !requestParam.getCommandId().isBlank();
        if (!hasAction && !hasCommand) {
            return null;
        }
        if (!hasAction || !hasCommand) {
            throw new ServiceException("订单 actionId 和 commandId 必须同时提供");
        }
        String actionId = requestParam.getActionId().trim();
        String normalized = requestParam.getCommandId().trim();
        if (!normalized.equals(actionId + ":create-order")) {
            throw new ServiceException("订单命令标识与操作标识不一致");
        }
        requestParam.setActionId(actionId);
        requestParam.setCommandId(normalized);
        return normalized;
    }

    /**
     * 读取同一用户分片内已经创建的订单命令。
     *
     * @param commandId 稳定命令标识
     * @param userId 用户分片键
     * @return 已创建订单，不存在时返回 null
     */
    private OrderDO findByCommandId(String commandId, String userId) {
        return orderMapper.selectOne(Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getUserId, userId)
                .eq(OrderDO::getCommandId, commandId));
    }

    /**
     * 校验重复命令仍绑定同一 action、用户和不可变参数。
     *
     * @param requestParam 当前创建参数
     * @param requestFingerprint 当前参数指纹
     * @param existing 已存在订单
     * @return 原订单号
     */
    private String resolveExistingCreateCommand(
            TicketOrderCreateReqDTO requestParam,
            String requestFingerprint,
            OrderDO existing) {
        if (!Objects.equals(existing.getActionId(), requestParam.getActionId())
                || !Objects.equals(existing.getRequestFingerprint(), requestFingerprint)
                || !Objects.equals(existing.getUserId(), String.valueOf(requestParam.getUserId()))) {
            throw new ServiceException("订单命令标识与原请求不一致");
        }
        return existing.getOrderSn();
    }

    /**
     * 计算不包含命令标识的订单创建参数指纹。
     *
     * @param requestParam 订单创建参数
     * @return SHA-256 十六进制摘要
     */
    private String createRequestFingerprint(TicketOrderCreateReqDTO requestParam) {
        CreateCommandPayload payload = new CreateCommandPayload(
                requestParam.getUserId(), requestParam.getUsername(), requestParam.getTrainId(),
                requestParam.getDeparture(), requestParam.getArrival(), requestParam.getRidingDate(),
                requestParam.getTrainNumber(), requestParam.getDepartureTime(), requestParam.getArrivalTime(),
                requestParam.getTicketOrderItems());
        try {
            // JSON 仅用于固定 DTO 字段顺序的摘要输入，不作为权威业务结果存储。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    JSON.toJSONString(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * 订单创建命令的不可变摘要载荷。
     */
    private record CreateCommandPayload(
            Long userId,
            String username,
            Long trainId,
            String departure,
            String arrival,
            java.util.Date ridingDate,
            String trainNumber,
            java.util.Date departureTime,
            java.util.Date arrivalTime,
            List<TicketOrderItemCreateReqDTO> items) {
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
     * 通过订单状态条件更新唯一认领关闭操作，并在同一事务内同步关闭待支付子订单。
     *
     * @param orderSn 订单号
     * @param verifyOwner 是否校验当前登录用户为订单所有者
     * @return 订单已经或本次成功关闭时返回 true
     */
    private boolean cancelTickOrderInternal(String orderSn, boolean verifyOwner) {
        // 先校验订单存在和归属；真正的并发裁决由后续带原状态的 UPDATE 完成。
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

        // 只有从待支付更新为已关闭的一行能够获得后续子订单和余票回滚的执行资格。
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setStatus(OrderStatusEnum.CLOSED.getStatus());
        int updateResult = orderMapper.update(updateOrderDO, Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn)
                .eq(OrderDO::getStatus, OrderStatusEnum.PENDING_PAYMENT.getStatus()));
        if (updateResult == 0) {
            // 与支付或其他关闭请求发生竞争时，按最新状态区分幂等关闭和非法状态迁移。
            OrderDO latestOrder = requireOrder(orderSn);
            if (Objects.equals(latestOrder.getStatus(), OrderStatusEnum.CLOSED.getStatus())) {
                return true;
            }
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
        }

        // 子订单也限定待支付原状态，避免关闭操作覆盖支付或退款后的明细状态。
        OrderItemDO updateOrderItemDO = new OrderItemDO();
        updateOrderItemDO.setStatus(OrderItemStatusEnum.CLOSED.getStatus());
        int updateItemResult = orderItemMapper.update(updateOrderItemDO, Wrappers.lambdaUpdate(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn)
                .eq(OrderItemDO::getStatus, OrderItemStatusEnum.PENDING_PAYMENT.getStatus()));
        if (updateItemResult <= 0) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_ERROR);
        }
        return true;
    }

    /**
     * 按请求指定的原状态原子反转订单及其全部子订单状态。
     *
     * @param requestParam 包含目标状态和期望原状态的反转参数
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void statusReversal(OrderStatusReversalDTO requestParam) {
        // 父订单的条件更新作为该状态迁移唯一执行权，禁止不同回调相互覆盖。
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setStatus(requestParam.getOrderStatus());
        int updateResult = orderMapper.update(updateOrderDO, Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn())
                .eq(OrderDO::getStatus, requestParam.getExpectedOrderStatus()));
        if (updateResult <= 0) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
        }

        // 全部子订单必须从相同期望状态迁移，父子状态任一失败都由事务整体回滚。
        OrderItemDO orderItemDO = new OrderItemDO();
        orderItemDO.setStatus(requestParam.getOrderItemStatus());
        int orderItemUpdateResult = orderItemMapper.update(orderItemDO, Wrappers.lambdaUpdate(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, requestParam.getOrderSn())
                .eq(OrderItemDO::getStatus, requestParam.getExpectedOrderItemStatus()));
        if (orderItemUpdateResult <= 0) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
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

    /**
     * 分页查询当前认证账号购买的订单，并补充首张车票摘要和服务端计算的可操作状态。
     *
     * @param requestParam 分页参数
     * @return 当前账号拥有的订单分页
     */
    @Override
    public PageResponse<TicketOrderDetailSelfRespDTO> pageSelfTicketOrder(TicketOrderSelfPageQueryReqDTO requestParam) {
        // MCP 的“我的订单”按下单账号归属查询，不能按实名认证身份证误筛为“本人乘车订单”。
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getUserId, UserContext.getUserId())
                .orderByDesc(OrderDO::getOrderTime);
        IPage<OrderDO> orderPage = orderMapper.selectPage(PageUtil.convert(requestParam), queryWrapper);
        return PageUtil.convert(orderPage, orderDO -> {
            // 列表只展示一张非敏感车票摘要，完整退票范围继续由订单详情和退票预览接口确定。
            LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, orderDO.getOrderSn());
            List<OrderItemDO> orderItems = orderItemMapper.selectList(orderItemQueryWrapper);
            OrderItemDO firstOrderItem = orderItems.stream().findFirst().orElse(null);
            TicketOrderDetailSelfRespDTO actualResult = BeanUtil.convert(orderDO, TicketOrderDetailSelfRespDTO.class);
            if (firstOrderItem != null) {
                BeanUtil.convertIgnoreNullAndBlank(firstOrderItem, actualResult);
            }

            // 可操作状态只依据订单状态和发车时间计算，回答模型不能自行推断。
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
