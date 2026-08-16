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
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.orderservice.common.enums.DelayCloseMessageStatusEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCanalErrorCodeEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderItemStatusEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemPassengerDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCommandMapper;
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
import org.opengoofy.index12306.biz.orderservice.mq.event.PayResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.orderservice.service.OrderItemService;
import org.opengoofy.index12306.biz.orderservice.service.OrderDelayCloseMessageDispatchService;
import org.opengoofy.index12306.biz.orderservice.service.OrderPassengerRelationService;
import org.opengoofy.index12306.biz.orderservice.service.OrderService;
import org.opengoofy.index12306.biz.orderservice.service.monitor.OrderCreateMetrics;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
    private final OrderCommandMapper orderCommandMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderItemService orderItemService;
    private final OrderPassengerRelationService orderPassengerRelationService;
    private final OrderDelayCloseMessageDispatchService orderDelayCloseMessageDispatchService;
    private final OrderCreateMetrics orderCreateMetrics;
    private final PlatformTransactionManager transactionManager;

    private static final String ORDER_COMMAND_PROCESSING = "PROCESSING";
    private static final String ORDER_COMMAND_SUCCEEDED = "SUCCEEDED";
    private static final String ORDER_COMMAND_FAILED = "FAILED";

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

    /**
     * 以顺序短事务创建订单，避免命令登记事务与订单事务同时占用两个数据库连接。
     *
     * @param requestParam 订单、乘车人和可选稳定命令参数
     * @return 新创建或幂等复用的订单号
     */
    @Override
    public String createTicketOrder(TicketOrderCreateReqDTO requestParam) {
        Timer.Sample totalTimer = orderCreateMetrics.startStageTimer();
        String result = "failed";
        try {
            // 对外接口只包裹总耗时，具体阶段由内部创建流程分别记录。
            String orderSn = doCreateTicketOrder(requestParam);
            result = "success";
            return orderSn;
        } finally {
            // 异常路径也必须停止样本，确保失败请求不会从耗时指标中消失。
            orderCreateMetrics.recordStage(totalTimer, "order_create_total", result);
        }
    }

    /**
     * 以顺序短事务执行命令登记、订单落库和提交后消息入队。
     *
     * @param requestParam 订单、乘车人和可选稳定命令参数
     * @return 新创建或幂等复用的订单号
     */
    private String doCreateTicketOrder(TicketOrderCreateReqDTO requestParam) {
        String commandId = normalizeCreateCommand(requestParam);
        String requestFingerprint = commandId == null ? null : createRequestFingerprint(requestParam);
        String userId = String.valueOf(requestParam.getUserId());
        OrderCommandDO command = null;
        if (commandId != null) {
            // 先独立提交 PROCESSING，远程调用方超时后才能区分未知、成功和明确失败。
            Timer.Sample commandPrepareTimer = orderCreateMetrics.startStageTimer();
            try {
                command = prepareOrderCommand(commandId, requestParam.getActionId(), userId, requestFingerprint);
                orderCreateMetrics.recordStage(commandPrepareTimer, "command_prepare", "success");
            } catch (RuntimeException ex) {
                orderCreateMetrics.recordStage(commandPrepareTimer, "command_prepare", "failed");
                throw ex;
            }
            if (ORDER_COMMAND_SUCCEEDED.equals(command.getStatus())) {
                // 成功命令若仍处于待发送状态，异步投递器会通过数据库认领保证只有一个实例发送。
                enqueueDelayCloseMessage(userId, commandId, command.getOrderSn());
                return command.getOrderSn();
            }
        }
        String createdOrderSn;
        Timer.Sample orderTransactionTimer = orderCreateMetrics.startStageTimer();
        try {
            // 命令登记已经提交后再开启订单事务，当前线程任一时刻最多占用一个数据库连接。
            createdOrderSn = executeInNewTransaction(() -> {
            // 网络重试优先读取同一用户分片内的原订单，避免再次生成订单号和子订单。
            OrderDO existing = commandId == null ? null : findByCommandId(commandId, userId);
            boolean reusedExistingOrder = existing != null;
            if (existing == null) {
                // 通过基因法将用户 ID 融入到订单号。
                String orderSn = OrderIdGeneratorManager.generateId(requestParam.getUserId());
                // 创建汇总订单，存储整个账单的公共情况。
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
                        .userId(userId)
                        .build();
                Timer.Sample orderInsertTimer = orderCreateMetrics.startStageTimer();
                try {
                    // 每个订单分片上的命令唯一约束是并发创建的最终防线。
                    orderMapper.insert(orderDO);
                    orderCreateMetrics.recordStage(orderInsertTimer, "order_insert", "success");
                } catch (DuplicateKeyException exception) {
                    orderCreateMetrics.recordStage(orderInsertTimer, "order_insert", "duplicate");
                    existing = findByCommandId(commandId, userId);
                    if (existing == null) {
                        throw exception;
                    }
                    reusedExistingOrder = true;
                } catch (RuntimeException exception) {
                    orderCreateMetrics.recordStage(orderInsertTimer, "order_insert", "failed");
                    throw exception;
                }
                if (existing == null) {
                    // 订单主记录已写入当前事务，继续写入乘车人和延迟关闭事件。
                    existing = orderDO;
                }
            }
            if (reusedExistingOrder) {
                // 重复命令只有在订单字段完全匹配时才能复用原订单号。
                String existingOrderSn = resolveExistingCreateCommand(requestParam, requestFingerprint, existing);
                completeOrderCommand(commandId, userId, existingOrderSn);
                return existingOrderSn;
            }
            String orderSn = existing.getOrderSn();
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
                        .userId(userId)
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
            Timer.Sample orderItemPersistTimer = orderCreateMetrics.startStageTimer();
            try {
                // 订单明细和乘车人关系共享当前订单事务，统一记录批量持久化耗时。
                orderItemService.saveBatch(orderItemDOList);
                orderPassengerRelationService.saveBatch(orderPassengerRelationDOList);
                orderCreateMetrics.recordStage(orderItemPersistTimer, "order_item_persist", "success");
            } catch (RuntimeException ex) {
                orderCreateMetrics.recordStage(orderItemPersistTimer, "order_item_persist", "failed");
                throw ex;
            }
            // 命令成功状态同时充当延迟关单 Outbox，提交后由后台投递器发送 RocketMQ。
            completeOrderCommand(commandId, userId, orderSn);
            return orderSn;
            });
            orderCreateMetrics.recordStage(orderTransactionTimer, "order_transaction", "success");
        } catch (RuntimeException ex) {
            orderCreateMetrics.recordStage(orderTransactionTimer, "order_transaction", "failed");
            // 失败终态单独提交，供 ticket-service 对 PREPARED 座位安全释放。
            markOrderCommandFailed(commandId, userId, ex);
            throw ex;
        }
        // 订单和 Outbox 已经提交，当前调用只入有界线程池，不等待 RocketMQ 网络确认。
        enqueueDelayCloseMessage(userId, commandId, createdOrderSn);
        return createdOrderSn;
    }

    /**
     * 记录延迟关单消息进入后台投递器的调用耗时，不等待 RocketMQ 网络发送完成。
     *
     * @param userId 订单用户标识
     * @param commandId 稳定创建命令标识
     * @param orderSn 已提交订单号
     */
    private void enqueueDelayCloseMessage(String userId, String commandId, String orderSn) {
        Timer.Sample enqueueTimer = orderCreateMetrics.startStageTimer();
        try {
            // 这里只提交内存任务，后台发送结果由 Outbox 状态和恢复扫描保证。
            orderDelayCloseMessageDispatchService.dispatchAsync(userId, commandId, orderSn);
            orderCreateMetrics.recordStage(enqueueTimer, "delay_close_enqueue", "success");
        } catch (RuntimeException ex) {
            orderCreateMetrics.recordStage(enqueueTimer, "delay_close_enqueue", "failed");
            throw ex;
        }
    }

    /**
     * 在当前订单事务中写入命令成功状态和延迟关单 Outbox 状态。
     *
     * @param commandId 稳定创建命令标识
     * @param userId 订单用户标识
     * @param orderSn 已创建或幂等复用的订单号
     */
    private void completeOrderCommand(String commandId, String userId, String orderSn) {
        Timer.Sample commandCompleteTimer = orderCreateMetrics.startStageTimer();
        try {
            // 成功命令与订单处于同一用户分片，更新随当前订单事务一起提交。
            markOrderCommandSucceeded(commandId, userId, orderSn);
            orderCreateMetrics.recordStage(commandCompleteTimer, "command_complete", "success");
        } catch (RuntimeException ex) {
            orderCreateMetrics.recordStage(commandCompleteTimer, "command_complete", "failed");
            throw ex;
        }
    }

    /**
     * 查询当前用户订单创建命令的权威状态。
     *
     * @param commandId 稳定命令标识
     * @return 未找到、处理中、已成功或已失败的安全结果
     */
    @Override
    public OrderCommandStatusRespDTO queryCommandStatus(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new ServiceException("订单命令标识不能为空");
        }
        String normalized = commandId.trim();
        String userId = UserContext.getUserId();
        OrderDO order = findByCommandId(normalized, userId);
        if (order != null) {
            if (!Objects.equals(order.getUserId(), userId)) {
                throw new ClientException("订单命令不存在或无权访问");
            }
            // 已落库订单永远优先于命令表，覆盖订单提交成功但命令成功标记尚未写入的短暂窗口。
            return OrderCommandStatusRespDTO.builder()
                    .commandId(order.getCommandId())
                    .actionId(order.getActionId())
                    .status(ORDER_COMMAND_SUCCEEDED)
                    .orderSn(order.getOrderSn())
                    .build();
        }
        OrderCommandDO command = findOrderCommand(normalized, userId);
        if (command == null) {
            return OrderCommandStatusRespDTO.builder()
                    .commandId(normalized)
                    .status("NOT_FOUND")
                    .build();
        }
        if (!Objects.equals(command.getUserId(), userId)) {
            throw new ClientException("订单命令不存在或无权访问");
        }
        // 查询始终带用户分片键，其他用户即使猜中 commandId 也无法读取订单号。
        return OrderCommandStatusRespDTO.builder()
                .commandId(command.getCommandId())
                .actionId(command.getActionId())
                .status(command.getStatus())
                .orderSn(command.getOrderSn())
                .build();
    }

    /**
     * 在订单主事务外登记稳定命令，确保远程超时后仍保留处理中的事实。
     *
     * @param commandId 稳定订单创建命令标识
     * @param actionId 订单创建动作标识
     * @param userId 订单所属用户标识
     * @param requestFingerprint 不可变请求参数摘要
     * @return 新建的处理中命令或既有成功命令
     */
    private OrderCommandDO prepareOrderCommand(String commandId, String actionId, String userId,
                                               String requestFingerprint) {
        try {
            return executeInNewTransaction(() -> {
                // 命令记录与订单使用同一 userId 分片键，避免跨分片查询产生广播路由。
                OrderCommandDO existing = findOrderCommand(commandId, userId);
                if (existing != null) {
                    return resolveExistingOrderCommand(existing, actionId, requestFingerprint);
                }
                OrderCommandDO command = OrderCommandDO.builder()
                        .commandId(commandId)
                        .actionId(actionId)
                        .userId(userId)
                        .requestFingerprint(requestFingerprint)
                        .status(ORDER_COMMAND_PROCESSING)
                        .build();
                if (orderCommandMapper.insert(command) != 1) {
                    throw new ServiceException("创建订单命令记录失败");
                }
                return command;
            });
        } catch (DuplicateKeyException exception) {
            // 并发请求可能同时经过空查询，唯一键冲突后读取已提交命令即可完成幂等裁决。
            OrderCommandDO existing = executeInNewTransaction(() -> findOrderCommand(commandId, userId));
            if (existing == null) {
                throw exception;
            }
            return resolveExistingOrderCommand(existing, actionId, requestFingerprint);
        }
    }

    /**
     * 校验重复稳定命令，并仅返回已经成功的原订单结果。
     *
     * @param existing 已持久化的命令记录
     * @param actionId 当前订单创建动作标识
     * @param requestFingerprint 当前不可变请求参数摘要
     * @return 可安全复用的成功命令记录
     */
    private OrderCommandDO resolveExistingOrderCommand(OrderCommandDO existing, String actionId,
                                                       String requestFingerprint) {
        if (!Objects.equals(existing.getActionId(), actionId)
                || !Objects.equals(existing.getRequestFingerprint(), requestFingerprint)) {
            throw new ServiceException("订单命令标识与原请求不一致");
        }
        if (ORDER_COMMAND_SUCCEEDED.equals(existing.getStatus()) && existing.getOrderSn() != null) {
            return existing;
        }
        if (ORDER_COMMAND_FAILED.equals(existing.getStatus())) {
            throw new ServiceException("订单命令已失败，不能重复创建");
        }
        // 处理中命令不能被第二个请求重放，避免原请求尚未结束时产生两个订单。
        throw new ServiceException("订单命令正在处理中");
    }

    /**
     * 在订单主事务中把稳定命令推进为成功，确保命令成功时订单必然一并提交。
     *
     * @param commandId 稳定订单创建命令标识
     * @param userId 订单所属用户标识
     * @param orderSn 已创建订单号
     */
    private void markOrderCommandSucceeded(String commandId, String userId, String orderSn) {
        if (commandId == null) {
            return;
        }
        // 只允许 PROCESSING 向成功迁移，失败或其他状态不能被后续请求覆盖。
        int updated = orderCommandMapper.update(
                OrderCommandDO.builder()
                        .status(ORDER_COMMAND_SUCCEEDED)
                        .orderSn(orderSn)
                        .delayCloseStatus(DelayCloseMessageStatusEnum.PENDING.getStatus())
                        .delayCloseRetryCount(0)
                        .delayCloseNextRetryTime(new Date())
                        .delayCloseFailureReason(null)
                        .build(),
                Wrappers.lambdaUpdate(OrderCommandDO.class)
                        .eq(OrderCommandDO::getUserId, userId)
                        .eq(OrderCommandDO::getCommandId, commandId)
                        .eq(OrderCommandDO::getStatus, ORDER_COMMAND_PROCESSING));
        if (updated != 1) {
            throw new ServiceException("更新订单命令成功状态失败");
        }
    }

    /**
     * 在订单主事务回滚后独立保存失败终态，供上游安全释放 PREPARED 座位。
     *
     * @param commandId 稳定订单创建命令标识
     * @param userId 订单所属用户标识
     * @param exception 当前失败异常
     */
    private void markOrderCommandFailed(String commandId, String userId, RuntimeException exception) {
        if (commandId == null) {
            return;
        }
        executeInNewTransaction(() -> {
            // 只将当前执行中的命令置失败，已成功命令不能因迟到异常被反向覆盖。
            orderCommandMapper.update(
                    OrderCommandDO.builder()
                            .status(ORDER_COMMAND_FAILED)
                            .failureReason(exception.getClass().getSimpleName())
                            .build(),
                    Wrappers.lambdaUpdate(OrderCommandDO.class)
                            .eq(OrderCommandDO::getUserId, userId)
                            .eq(OrderCommandDO::getCommandId, commandId)
                            .eq(OrderCommandDO::getStatus, ORDER_COMMAND_PROCESSING));
            return null;
        });
    }

    /**
     * 使用独立事务执行一个数据库阶段，各阶段顺序提交且不会同时占用两个连接。
     *
     * @param callback 需要独立提交的数据库操作
     * @return 回调返回值
     */
    private <T> T executeInNewTransaction(java.util.function.Supplier<T> callback) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 调用方完成当前阶段后立即提交，再开始下一阶段，避免连接池在嵌套事务中耗尽。
        return transactionTemplate.execute(status -> callback.get());
    }

    /**
     * 校验并规范化订单创建命令，缺失时为直接调用方生成服务端命令。
     *
     * @param requestParam 订单创建参数
     * @return 规范化或服务端生成的命令标识
     */
    private String normalizeCreateCommand(TicketOrderCreateReqDTO requestParam) {
        boolean hasAction = requestParam.getActionId() != null && !requestParam.getActionId().isBlank();
        boolean hasCommand = requestParam.getCommandId() != null && !requestParam.getCommandId().isBlank();
        if (!hasAction && !hasCommand) {
            // 普通 Ticket 调用会提供稳定命令；兼容直接调用时生成一次性命令以确保 Outbox 不缺失。
            String actionId = "order-" + UUID.randomUUID();
            String commandId = actionId + ":create-order";
            requestParam.setActionId(actionId);
            requestParam.setCommandId(commandId);
            return commandId;
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
     * 按用户分片键和稳定命令读取命令记录。
     *
     * @param commandId 稳定订单创建命令标识
     * @param userId 订单所属用户标识
     * @return 命令记录，不存在时返回 null
     */
    private OrderCommandDO findOrderCommand(String commandId, String userId) {
        return orderCommandMapper.selectOne(Wrappers.lambdaQuery(OrderCommandDO.class)
                .eq(OrderCommandDO::getUserId, userId)
                .eq(OrderCommandDO::getCommandId, commandId));
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
