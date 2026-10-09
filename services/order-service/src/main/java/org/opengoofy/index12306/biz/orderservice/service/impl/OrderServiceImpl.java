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
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCanalErrorCodeEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderItemStatusEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCommandStatusEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCdcOutboxEventDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemPassengerDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCommandMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCdcOutboxEventMapper;
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
import org.opengoofy.index12306.biz.orderservice.service.OrderPassengerRelationService;
import org.opengoofy.index12306.biz.orderservice.service.OrderService;
import org.opengoofy.index12306.biz.orderservice.service.monitor.OrderCreateMetrics;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.page.PageResponse;
import org.opengoofy.index12306.framework.starter.database.toolkit.PageUtil;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateResultMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderDelayCloseEvent;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderDelayCloseMessage;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateItem;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateRequest;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderMessageConstants;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Instant;

/**
 * 订单服务接口层实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderCommandMapper orderCommandMapper;
    private final OrderCdcOutboxEventMapper orderCdcOutboxEventMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderItemService orderItemService;
    private final OrderPassengerRelationService orderPassengerRelationService;
    private final OrderCreateMetrics orderCreateMetrics;
    private final PlatformTransactionManager transactionManager;

    private static final String ORDER_COMMAND_PROCESSING = OrderCommandStatusEnum.PROCESSING.name();
    private static final String ORDER_COMMAND_SUCCEEDED = OrderCommandStatusEnum.SUCCEEDED.name();
    private static final String ORDER_COMMAND_FAILED = OrderCommandStatusEnum.FAILED.name();
    private static final long COMMAND_PAYLOAD_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final long COMMAND_RETRY_BASE_MILLIS = 1000L;
    private static final long COMMAND_RETRY_MAX_MILLIS = 60_000L;
    private static final long COMMAND_UNKNOWN_RECHECK_MILLIS = 300_000L;

    @Value("${unique-name:}")
    private String rocketMqUniqueName;

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
     * 恢复并执行已被当前工作线程持租约认领的订单创建 Command。
     *
     * @param command 包含加密载荷、租约所有者和 fencing token 的 Command
     */
    @Override
    public void executeClaimedOrderCommand(OrderCommandDO command) {
        Timer.Sample totalTimer = orderCreateMetrics.startStageTimer();
        String result = "failed";
        try {
            // 只有租约持有者读取并解密请求快照，普通消息重投和恢复扫描不会加载乘客敏感字段。
            TicketOrderCreateReqDTO requestParam = restoreOrderCreateRequest(command);
            // 成功结果在订单事务内入 Outbox，避免订单提交后服务宕机导致票务侧永远等待。
            doCreateTicketOrder(command, requestParam);
            result = "success";
        } catch (RuntimeException exception) {
            // 主事务回滚后按订单事实和失败确定性收敛状态；状态写入失败则保留租约等待恢复。
            settleClaimedCommandFailure(command, exception);
        } finally {
            // Command 执行路径记录完整建单耗时，便于区分入库接管和实际订单创建延迟。
            orderCreateMetrics.recordStage(totalTimer, "order_create_total", result);
        }
    }

    /**
     * 在一个短事务内提交订单、Command 成功状态、延迟关单和结果 CDC Outbox。
     *
     * @param command 当前工作线程持有围栏的订单创建 Command
     * @param requestParam 从 Command 加密载荷恢复出的订单请求
     * @return 新创建或幂等复用的订单号
     */
    private String doCreateTicketOrder(
            OrderCommandDO command,
            TicketOrderCreateReqDTO requestParam) {
        String reservationId = command.getReservationId();
        String requestFingerprint = command.getRequestFingerprint();
        String userId = command.getUserId();
        OrderCreateResultContext resultContext = resultContext(command);
        String createdOrderSn;
        Timer.Sample orderTransactionTimer = orderCreateMetrics.startStageTimer();
        try {
            // Command 已由 MQ 消费事务持久化；这里只开启订单本地事务，不嵌套占用第二个连接。
            createdOrderSn = executeInNewTransaction(() -> {
            // 网络重试优先读取同一用户分片内的原订单，避免再次生成订单号和子订单。
            OrderDO existing = reservationId == null ? null : findByOrderSn(reservationId, userId);
            boolean reusedExistingOrder = existing != null;
            if (existing == null) {
                // reservationId 在锁座时已经生成，订单服务只负责将它确认为最终订单号。
                String orderSn = reservationId;
                // 创建汇总订单，存储整个账单的公共情况。
                OrderDO orderDO = OrderDO.builder()
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
                    existing = findByOrderSn(reservationId, userId);
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
                completeOrderCommand(command, existingOrderSn);
                // 订单、命令状态和结果事件共享当前本地事务。
                enqueueOrderCreateResult(resultContext, TicketOrderMessageConstants.RESULT_SUCCEEDED,
                        null);
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
            // Command 与延迟关单 CDC Outbox 在同一订单事务提交，避免建单成功后丢失关单任务。
            completeOrderCommand(command, orderSn);
            // 订单主记录、命令成功状态和回传结果事件必须原子提交。
            enqueueOrderCreateResult(resultContext, TicketOrderMessageConstants.RESULT_SUCCEEDED, null);
            return orderSn;
            });
            orderCreateMetrics.recordStage(orderTransactionTimer, "order_transaction", "success");
        } catch (RuntimeException ex) {
            orderCreateMetrics.recordStage(orderTransactionTimer, "order_transaction", "failed");
            // 订单事务已经整体回滚，外层再根据失败确定性决定重试、未知或最终失败。
            throw ex;
        }
        return createdOrderSn;
    }

    /**
     * 在当前订单事务中写入命令成功状态和延迟关单 CDC Outbox。
     *
     * @param command 当前执行租约和 fencing token
     * @param orderSn 已创建或幂等复用的订单号
     */
    private void completeOrderCommand(OrderCommandDO command, String orderSn) {
        Timer.Sample commandCompleteTimer = orderCreateMetrics.startStageTimer();
        try {
            // 关单事件和订单、Command 同事务追加，由 Canal Relay 负责 Broker 投递与位点恢复。
            appendOrderDelayCloseEvent(command, orderSn);
            // 成功命令与订单处于同一用户分片，状态更新随当前订单事务一起提交。
            markOrderCommandSucceeded(command);
            orderCreateMetrics.recordStage(commandCompleteTimer, "command_complete", "success");
        } catch (RuntimeException ex) {
            orderCreateMetrics.recordStage(commandCompleteTimer, "command_complete", "failed");
            throw ex;
        }
    }

    /**
     * 查询当前用户订单创建命令的权威状态。
     *
     * @param reservationId 稳定购票受理标识
     * @return 未找到、处理中、已成功或已失败的安全结果
     */
    @Override
    public OrderCommandStatusRespDTO queryReservationOrderStatus(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new ServiceException("购票受理标识不能为空");
        }
        String normalized = reservationId.trim();
        String userId = UserContext.getUserId();
        OrderDO order = findByOrderSn(normalized, userId);
        if (order != null) {
            if (!Objects.equals(order.getUserId(), userId)) {
                throw new ClientException("订单命令不存在或无权访问");
            }
            // 已落库订单永远优先于命令表，覆盖订单提交成功但命令成功标记尚未写入的短暂窗口。
            return OrderCommandStatusRespDTO.builder()
                    .reservationId(normalized)
                    .status(ORDER_COMMAND_SUCCEEDED)
                    .orderSn(order.getOrderSn())
                    .build();
        }
        OrderCommandDO command = findOrderCommand(normalized, userId);
        if (command == null) {
            return OrderCommandStatusRespDTO.builder()
                    .reservationId(normalized)
                    .status("NOT_FOUND")
                    .build();
        }
        if (!Objects.equals(command.getUserId(), userId)) {
            throw new ClientException("订单命令不存在或无权访问");
        }
        // 查询始终带用户分片键，其他用户即使猜中 reservationId 也无法读取订单号。
        return OrderCommandStatusRespDTO.builder()
                .reservationId(command.getReservationId())
                .status(command.getStatus())
                .orderSn(ORDER_COMMAND_SUCCEEDED.equals(command.getStatus()) ? command.getReservationId() : null)
                .build();
    }

    /**
     * 解密并校验 Command 请求快照，再转换为订单领域请求。
     *
     * @param command 当前持有执行租约的完整 Command
     * @return 可用于订单落库的领域请求
     */
    private TicketOrderCreateReqDTO restoreOrderCreateRequest(OrderCommandDO command) {
        try {
            // 身份、状态和围栏字段缺失说明记录不能安全执行，应形成明确的载荷失败而不是猜测补全。
            if (command == null
                    || !ORDER_COMMAND_PROCESSING.equals(command.getStatus())
                    || command.getLeaseOwner() == null
                    || command.getLeaseOwner().isBlank()
                    || command.getLeaseToken() == null
                    || command.getLeaseToken() <= 0
                    || command.getRequestPayload() == null
                    || command.getRequestPayload().isBlank()
                    || !Objects.equals(command.getRequestFingerprint(), fingerprint(command.getRequestPayload()))) {
                throw new InvalidOrderCommandPayloadException("订单 Command 载荷或租约不完整");
            }
            TicketOrderCreateRequest source = JSON.parseObject(
                    command.getRequestPayload(), TicketOrderCreateRequest.class);
            if (source == null
                    || source.getUserId() == null
                    || !Objects.equals(source.getUserId().trim(), command.getUserId())
                    || source.getTicketOrderItems() == null
                    || source.getTicketOrderItems().isEmpty()) {
                throw new InvalidOrderCommandPayloadException("订单 Command 请求身份不一致");
            }
            TicketOrderCreateReqDTO target = new TicketOrderCreateReqDTO();
            target.setUserId(Long.parseLong(source.getUserId().trim()));
            target.setUsername(source.getUsername());
            target.setTrainId(source.getTrainId());
            target.setDeparture(source.getDeparture());
            target.setArrival(source.getArrival());
            target.setSource(source.getSource());
            target.setOrderTime(source.getOrderTime());
            target.setRidingDate(source.getRidingDate());
            target.setTrainNumber(source.getTrainNumber());
            target.setDepartureTime(source.getDepartureTime());
            target.setArrivalTime(source.getArrivalTime());
            // 座位和乘客字段使用持久化时冻结的快照，执行阶段不再远程查询可变数据。
            target.setTicketOrderItems(source.getTicketOrderItems().stream()
                    .map(this::restoreOrderCreateItem)
                    .toList());
            return target;
        } catch (InvalidOrderCommandPayloadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidOrderCommandPayloadException("订单 Command 载荷无法解析", exception);
        }
    }

    /**
     * 将共享协议中的单张车票快照转换为订单明细请求。
     *
     * @param source Command 中的车票快照
     * @return 订单领域车票明细
     */
    private TicketOrderItemCreateReqDTO restoreOrderCreateItem(TicketOrderCreateItem source) {
        if (source == null) {
            throw new InvalidOrderCommandPayloadException("订单 Command 包含空车票明细");
        }
        TicketOrderItemCreateReqDTO target = new TicketOrderItemCreateReqDTO();
        // 这里只做协议到领域的逐字段转换，不重新选择或修改票务服务已经锁定的座位。
        target.setCarriageNumber(source.getCarriageNumber());
        target.setSeatType(source.getSeatType());
        target.setSeatNumber(source.getSeatNumber());
        target.setRealName(source.getRealName());
        target.setIdType(source.getIdType());
        target.setIdCard(source.getIdCard());
        target.setPhone(source.getPhone());
        target.setAmount(source.getAmount());
        target.setTicketType(source.getTicketType());
        return target;
    }

    /**
     * 在订单事务失败后对账订单事实，并以 fencing token 推进 FAILED、RETRY_WAIT 或 UNKNOWN。
     *
     * @param command 本次执行持有的 Command 租约
     * @param exception 本次执行异常
     */
    private void settleClaimedCommandFailure(OrderCommandDO command, RuntimeException exception) {
        executeInNewTransaction(() -> {
            // 若提交结果不明但订单已经存在，优先补齐成功终态，绝不能向票务侧发送失败释放座位。
            OrderDO existing = findByOrderSn(command.getReservationId(), command.getUserId());
            if (existing != null) {
                if (matchesCommand(existing, command)) {
                    // 订单已提交但 Command 终态未知时，同一事务补齐延迟关单和结果 CDC 事件。
                    appendOrderDelayCloseEvent(command, existing.getOrderSn());
                    markOrderCommandSucceeded(command);
                    enqueueOrderCreateResult(resultContext(command), TicketOrderMessageConstants.RESULT_SUCCEEDED,
                            null);
                    return existing.getOrderSn();
                }
                markClaimedCommandUnknown(command, "ORDER_FACT_CONFLICT");
                return null;
            }
            if (exception instanceof InvalidOrderCommandPayloadException) {
                // 载荷解析或身份摘要错误在订单落库前即可确定，只有此类明确失败允许发布释放结果。
                markClaimedCommandFailed(command, "INVALID_COMMAND_PAYLOAD");
            } else if (defaultAttemptCount(command.getAttemptCount())
                    >= defaultMaxAttempts(command.getMaxAttempts())) {
                // 基础设施异常耗尽次数仍不能证明订单失败，进入 UNKNOWN 等待事实对账和人工处理。
                markClaimedCommandUnknown(command, "ATTEMPTS_EXHAUSTED");
            } else {
                markClaimedCommandRetry(command, exception.getClass().getSimpleName());
            }
            return null;
        });
    }

    /**
     * 对账 UNKNOWN Command，只在同一命令的订单事实存在时补齐成功状态和 CDC 事件。
     *
     * @param command 恢复扫描返回的 UNKNOWN Command 快照
     */
    @Override
    public void reconcileUnknownOrderCommand(OrderCommandDO command) {
        executeInNewTransaction(() -> {
            // UNKNOWN 没有执行租约，多个实例可并发读取，但条件更新只允许一个实例完成对账。
            OrderDO existing = findByOrderSn(command.getReservationId(), command.getUserId());
            if (existing == null || !matchesCommand(existing, command)) {
                scheduleUnknownRecheck(command, existing == null ? "ORDER_FACT_NOT_FOUND" : "ORDER_FACT_CONFLICT");
                return null;
            }
            int updated = markUnknownCommandSucceeded(command);
            if (updated == 1) {
                // 对账成功后补齐 CDC Outbox；事务回滚时不会留下无订单的关单消息。
                appendOrderDelayCloseEvent(command, existing.getOrderSn());
                // 成功状态与结果事件共享本地事务，重复扫描不会生成另一条结果事件。
                enqueueOrderCreateResult(resultContext(command), TicketOrderMessageConstants.RESULT_SUCCEEDED,
                        null);
                return existing.getOrderSn();
            }
            return null;
        });
    }

    /**
     * 使用当前租约 fencing token 将 Command 和订单原子推进为成功。
     *
     * @param command 当前工作线程持有的租约
     * @param orderSn 已创建或幂等复用的订单号
     */
    private void markOrderCommandSucceeded(OrderCommandDO command) {
        Date now = new Date();
        // 所有者和单调 token 同时匹配才允许提交；租约过期后迟到事务会因更新零行而整体回滚。
        int updated = orderCommandMapper.update(null, fencedCommandUpdate(command)
                .set("status", ORDER_COMMAND_SUCCEEDED)
                .set("finish_time", now)
                .set("payload_expire_time", new Date(now.getTime() + COMMAND_PAYLOAD_RETENTION_MILLIS))
                .set("next_retry_time", null)
                .set("lease_owner", null)
                .set("lease_expire_time", null)
                .set("failure_reason", null));
        if (updated != 1) {
            throw new ServiceException("订单 Command 执行围栏已失效");
        }
    }

    /**
     * 将明确无效的 Command 置为最终失败并在同一事务写入失败结果 Outbox。
     *
     * @param command 当前工作线程持有的租约
     * @param failureCategory 可公开的稳定失败分类
     */
    private void markClaimedCommandFailed(OrderCommandDO command, String failureCategory) {
        Date now = new Date();
        // FAILED 是唯一允许释放座位的终态，因此只接受当前 fencing token 的明确载荷失败。
        int updated = orderCommandMapper.update(null, fencedCommandUpdate(command)
                .set("status", ORDER_COMMAND_FAILED)
                .set("finish_time", now)
                .set("payload_expire_time", new Date(now.getTime() + COMMAND_PAYLOAD_RETENTION_MILLIS))
                .set("next_retry_time", null)
                .set("lease_owner", null)
                .set("lease_expire_time", null)
                .set("failure_reason", failureCategory));
        if (updated == 1) {
            // 票务服务只有消费到这个与 FAILED 原子提交的结果，才可以释放 PREPARED 座位。
            enqueueOrderCreateResult(resultContext(command), TicketOrderMessageConstants.RESULT_FAILED,
                    failureCategory);
        }
    }

    /**
     * 将可重试异常按指数退避写回 RETRY_WAIT 并释放当前执行租约。
     *
     * @param command 当前工作线程持有的租约
     * @param failureCategory 不含敏感正文的异常分类
     */
    private void markClaimedCommandRetry(OrderCommandDO command, String failureCategory) {
        int attemptCount = defaultAttemptCount(command.getAttemptCount());
        long retryDelay = Math.min(COMMAND_RETRY_MAX_MILLIS,
                COMMAND_RETRY_BASE_MILLIS * (1L << Math.min(Math.max(attemptCount - 1, 0), 10)));
        // RETRY_WAIT 不发布结果，恢复扫描到达 next_retry_time 后才能再次竞争新租约。
        orderCommandMapper.update(null, fencedCommandUpdate(command)
                .set("status", OrderCommandStatusEnum.RETRY_WAIT.name())
                .set("next_retry_time", new Date(System.currentTimeMillis() + retryDelay))
                .set("lease_owner", null)
                .set("lease_expire_time", null)
                .set("failure_reason", abbreviateFailure(failureCategory)));
    }

    /**
     * 将当前执行收敛为 UNKNOWN 并安排下一次订单事实对账。
     *
     * @param command 当前工作线程持有的租约
     * @param failureCategory 未确定结果的安全分类
     */
    private void markClaimedCommandUnknown(OrderCommandDO command, String failureCategory) {
        // UNKNOWN 保留载荷且不产生失败事件，避免提交结果不明时错误释放座位。
        orderCommandMapper.update(null, fencedCommandUpdate(command)
                .set("status", OrderCommandStatusEnum.UNKNOWN.name())
                .set("next_retry_time", new Date(System.currentTimeMillis() + COMMAND_UNKNOWN_RECHECK_MILLIS))
                .set("lease_owner", null)
                .set("lease_expire_time", null)
                .set("failure_reason", abbreviateFailure(failureCategory)));
    }

    /**
     * 为 PROCESSING 状态构造同时校验所有者和 token 的条件更新。
     *
     * @param command 当前租约快照
     * @return 已绑定执行围栏条件的更新器
     */
    private com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<OrderCommandDO> fencedCommandUpdate(
            OrderCommandDO command) {
        // userId 保证单分片路由，owner 和 token 阻止租约过期后的迟到线程覆盖新执行结果。
        return Wrappers.<OrderCommandDO>update()
                .eq("user_id", command.getUserId())
                .eq("reservation_id", command.getReservationId())
                .eq("status", ORDER_COMMAND_PROCESSING)
                .eq("lease_owner", command.getLeaseOwner())
                .eq("lease_token", command.getLeaseToken());
    }

    /**
     * 将 UNKNOWN Command 条件推进为成功，并返回实际更新行数供并发对账裁决。
     *
     * @param command UNKNOWN Command 快照
     * @param orderSn 已存在订单号
     * @return 成功更新一行时返回 1
     */
    private int markUnknownCommandSucceeded(OrderCommandDO command) {
        Date now = new Date();
        // UNKNOWN 对账不持租约，状态条件与 userId 路由足以保证只有一个实例推进终态。
        return orderCommandMapper.update(null, Wrappers.<OrderCommandDO>update()
                .set("status", ORDER_COMMAND_SUCCEEDED)
                .set("finish_time", now)
                .set("payload_expire_time", new Date(now.getTime() + COMMAND_PAYLOAD_RETENTION_MILLIS))
                .set("next_retry_time", null)
                .set("failure_reason", null)
                .eq("user_id", command.getUserId())
                .eq("reservation_id", command.getReservationId())
                .eq("status", OrderCommandStatusEnum.UNKNOWN.name()));
    }

    /**
     * 延后下一次 UNKNOWN 对账并保存不含敏感数据的事实分类。
     *
     * @param command UNKNOWN Command 快照
     * @param reason 本次未能确认成功的原因
     */
    private void scheduleUnknownRecheck(OrderCommandDO command, String reason) {
        // 条件更新避免旧扫描快照覆盖已经被其他实例对账成功的终态。
        orderCommandMapper.update(null, Wrappers.<OrderCommandDO>update()
                .set("next_retry_time", new Date(System.currentTimeMillis() + COMMAND_UNKNOWN_RECHECK_MILLIS))
                .set("failure_reason", abbreviateFailure(reason))
                .eq("user_id", command.getUserId())
                .eq("reservation_id", command.getReservationId())
                .eq("status", OrderCommandStatusEnum.UNKNOWN.name()));
    }

    /**
     * 判断订单事实仍与原始 Command 的不可变身份一致。
     *
     * @param order 已存在订单
     * @param command 原始 Command
     * @return 用户和请求摘要全部一致时返回 true
     */
    private boolean matchesCommand(OrderDO order, OrderCommandDO command) {
        // reservationId 已用于查询，再校验用户和请求摘要，防止错误复用冲突订单。
        return Objects.equals(order.getUserId(), command.getUserId())
                && Objects.equals(order.getRequestFingerprint(), command.getRequestFingerprint());
    }

    /**
     * 计算当前已执行次数的非负值。
     *
     * @param attemptCount 数据库存储值
     * @return 非负尝试次数
     */
    private int defaultAttemptCount(Integer attemptCount) {
        // 兼容迁移前空值；新任务始终由数据库默认值和认领更新维护。
        return attemptCount == null ? 0 : Math.max(attemptCount, 0);
    }

    /**
     * 计算 Command 允许的最小有效尝试次数。
     *
     * @param maxAttempts 数据库存储值
     * @return 至少为 1 的最大尝试次数
     */
    private int defaultMaxAttempts(Integer maxAttempts) {
        // 非法空值按一次执行处理，避免任务无限重试。
        return maxAttempts == null ? 1 : Math.max(maxAttempts, 1);
    }

    /**
     * 截断可持久化的失败分类，不保存可能含乘客参数的异常正文。
     *
     * @param value 异常类型或事实分类
     * @return 最长 128 字符的安全分类
     */
    private String abbreviateFailure(String value) {
        // 空分类统一收敛为 UNKNOWN_FAILURE，便于指标聚合和人工排查。
        String normalized = value == null || value.isBlank() ? "UNKNOWN_FAILURE" : value;
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    /**
     * 将订单创建终态写入当前事务的结果 Outbox，确保订单状态与票务回传事件原子提交。
     *
     * @param resultContext 源消息和座位预留上下文
     * @param status 已确定的订单创建结果状态
     * @param orderSn 成功时的订单号
     * @param failureCategory 失败时的稳定错误分类
     */
    private void enqueueOrderCreateResult(
            OrderCreateResultContext resultContext,
            String status,
            String failureCategory) {
        if (resultContext == null) {
            return;
        }
        // reservationId 是业务去重键，订单服务重放时只会复用同一条不可变结果事件。
        TicketOrderCreateResultMessage message = resultMessage(
                resultContext, status, failureCategory);
        appendOrderCreateResultEvent(resultContext, message);
    }

    /**
     * 追加待支付订单的延迟关单 CDC 事件，并在重复恢复时校验原事件没有被错误复用。
     *
     * @param command 当前订单创建 Command，提供分库分表路由和稳定幂等键
     * @param orderSn 已在当前事务创建或复用的待支付订单号
     */
    private void appendOrderDelayCloseEvent(OrderCommandDO command, String orderSn) {
        if (command == null || !hasText(command.getUserId()) || !hasText(command.getReservationId())
                || command.getRecoveryShard() == null || !hasText(orderSn)) {
            throw new IllegalArgumentException("延迟关单 CDC Outbox 缺少不可变路由信息");
        }
        // 载荷包含既有消息包装器同名字段，已在 Broker 中等待的旧消息和新事件共用票务消费语义。
        String eventId = command.getReservationId() + ":delay-close";
        TicketOrderDelayCloseMessage message = TicketOrderDelayCloseMessage.builder()
                .messageId(eventId)
                .messageVersion(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_VERSION)
                .commandId(command.getReservationId())
                .keys(orderSn)
                .message(TicketOrderDelayCloseEvent.builder().orderSn(orderSn).build())
                .uuid(eventId)
                // 固定值使重复 Command 恢复产生字节一致的不可变 Outbox 载荷。
                .timestamp(0L)
                .build();
        String uniqueName = rocketMqUniqueName == null ? "" : rocketMqUniqueName;
        OrderCdcOutboxEventDO event = OrderCdcOutboxEventDO.builder()
                .userId(command.getUserId())
                .commandId(command.getReservationId())
                .recoveryShard(command.getRecoveryShard())
                .namespace(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_NAMESPACE)
                .eventId(eventId)
                .eventType(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_EVENT_TYPE)
                .aggregateId(orderSn)
                .deduplicationKey(command.getReservationId())
                .messageKey(orderSn)
                .destinationTopic(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_TOPIC + uniqueName)
                .destinationTag(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_TAG + uniqueName)
                .delayLevel(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_DELAY_LEVEL)
                .payload(JSON.toJSONString(message))
                .eventVersion(TicketOrderMessageConstants.DELAY_CLOSE_ORDER_VERSION)
                .createdAt(new Date())
                .build();
        // 唯一键处理重复执行；命中旧记录时必须确认它与当前订单的延迟关单事实一致。
        if (orderCdcOutboxEventMapper.insertIgnore(event) == 0) {
            OrderCdcOutboxEventDO existing = orderCdcOutboxEventMapper.selectByRoutingAndDeduplicationKey(
                    event.getUserId(), event.getCommandId(), event.getRecoveryShard(),
                    event.getNamespace(), event.getDeduplicationKey());
            if (!sameOrderCdcOutboxEvent(existing, event)) {
                throw new ServiceException("延迟关单 CDC Outbox 幂等键绑定了不同事件");
            }
        }
    }

    /**
     * 追加订单创建终态对应的 CDC Outbox 事件，并拒绝同一幂等键绑定不同结果载荷。
     *
     * @param resultContext Command 持久化的稳定路由和关联标识
     * @param message 已确定成功或失败的不可变结果消息
     */
    private void appendOrderCreateResultEvent(
            OrderCreateResultContext resultContext,
            TicketOrderCreateResultMessage message) {
        // Outbox 与订单、Command 同路由，保证终态和回传事实位于同一分库分表事务。
        OrderCdcOutboxEventDO event = OrderCdcOutboxEventDO.builder()
                .userId(resultContext.userId())
                .commandId(resultContext.reservationId())
                .recoveryShard(resultContext.recoveryShard())
                .namespace(TicketOrderMessageConstants.CREATE_ORDER_RESULT_NAMESPACE)
                .eventId(message.getMessageId())
                .eventType(TicketOrderMessageConstants.CREATE_ORDER_RESULT_EVENT_TYPE)
                .aggregateId(resultContext.reservationId())
                .deduplicationKey(resultContext.reservationId())
                .messageKey(resultContext.reservationId())
                .destinationTopic(TicketOrderMessageConstants.CREATE_ORDER_TOPIC)
                .destinationTag(TicketOrderMessageConstants.CREATE_ORDER_RESULT_TAG)
                .delayLevel(0)
                .payload(JSON.toJSONString(message))
                .eventVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .createdAt(new Date())
                .build();
        // 唯一键处理重复恢复；重复插入后必须确认原事件没有被错误业务复用。
        if (orderCdcOutboxEventMapper.insertIgnore(event) == 0) {
            OrderCdcOutboxEventDO existing = orderCdcOutboxEventMapper.selectByRoutingAndDeduplicationKey(
                    event.getUserId(), event.getCommandId(), event.getRecoveryShard(),
                    event.getNamespace(), event.getDeduplicationKey());
            if (!sameOrderCdcOutboxEvent(existing, event)) {
                throw new ServiceException("订单创建结果 CDC Outbox 幂等键绑定了不同事件");
            }
        }
    }

    /**
     * 判断已存在的订单 CDC 事件是否与当前事务准备追加的事件完全一致。
     *
     * @param existing 数据库中已提交的事件
     * @param expected 当前事务希望提交的事件
     * @return 路由、协议和载荷字段完全一致时返回 true
     */
    private boolean sameOrderCdcOutboxEvent(OrderCdcOutboxEventDO existing, OrderCdcOutboxEventDO expected) {
        // 主键和创建时间由数据库或本次事务生成，不影响同一业务结果的重放判定。
        return existing != null
                && Objects.equals(existing.getUserId(), expected.getUserId())
                && Objects.equals(existing.getCommandId(), expected.getCommandId())
                && Objects.equals(existing.getRecoveryShard(), expected.getRecoveryShard())
                && Objects.equals(existing.getNamespace(), expected.getNamespace())
                && Objects.equals(existing.getEventId(), expected.getEventId())
                && Objects.equals(existing.getEventType(), expected.getEventType())
                && Objects.equals(existing.getAggregateId(), expected.getAggregateId())
                && Objects.equals(existing.getDeduplicationKey(), expected.getDeduplicationKey())
                && Objects.equals(existing.getMessageKey(), expected.getMessageKey())
                && Objects.equals(existing.getDestinationTopic(), expected.getDestinationTopic())
                && Objects.equals(existing.getDestinationTag(), expected.getDestinationTag())
                && Objects.equals(existing.getDelayLevel(), expected.getDelayLevel())
                && Objects.equals(existing.getPayload(), expected.getPayload())
                && Objects.equals(existing.getEventVersion(), expected.getEventVersion());
    }

    /**
     * 判断稳定路由或业务标识是否包含有效字符。
     *
     * @param value 待检查的标识值
     * @return 非空且不全为空白时返回 true
     */
    private boolean hasText(String value) {
        // CDC Outbox 的路由键必须来自已持久化事实，空字符串不能参与分库或幂等校验。
        return value != null && !value.isBlank();
    }

    /**
     * 构建返回给票务服务的不可变订单创建结果消息。
     *
     * @param resultContext 源消息和座位预留上下文
     * @param status 已确定的订单创建结果状态
     * @param failureCategory 失败时的稳定错误分类
     * @return 可写入 Outbox 或直接返回消费者的结果消息
     */
    private TicketOrderCreateResultMessage resultMessage(
            OrderCreateResultContext resultContext,
            String status,
            String failureCategory) {
        // 结果消息 ID 可由 reservationId 稳定推导，Broker 重投时不会生成新的消费者 Inbox 主键。
        return TicketOrderCreateResultMessage.builder()
                .messageId(resultContext.reservationId() + ":result")
                .messageVersion(TicketOrderMessageConstants.CREATE_ORDER_VERSION)
                .reservationId(resultContext.reservationId())
                .status(status)
                .failureCategory(failureCategory)
                .build();
    }

    /**
     * 从持久化 Command 构造订单结果事件所需的稳定关联上下文。
     *
     * @param command 订单创建 Command
     * @return 座位预留和订单结果路由上下文
     */
    private OrderCreateResultContext resultContext(OrderCommandDO command) {
        // 所有关联标识都来自首次持久化的不可变字段，恢复执行不会生成新的消息或预留编号。
        return new OrderCreateResultContext(
                command.getUserId(), command.getRecoveryShard(), command.getReservationId());
    }

    /**
     * 计算原始请求 JSON 的 SHA-256 摘要，用于执行前检测载荷损坏或错误解密。
     *
     * @param requestPayload 解密后的原始请求 JSON
     * @return 十六进制请求摘要
     */
    private String fingerprint(String requestPayload) {
        try {
            // 摘要算法必须与 Command 接收持久化阶段一致，不能重新序列化后再计算。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(requestPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * 保存订单结果事件所需的预留座位和稳定路由标识。
     */
    private record OrderCreateResultContext(
            String userId,
            Integer recoveryShard,
            String reservationId) {
    }

    /**
     * 表示在订单落库前即可确认的 Command 载荷错误。
     */
    private static final class InvalidOrderCommandPayloadException extends RuntimeException {

        /**
         * 创建不包含底层敏感数据的载荷异常。
         *
         * @param message 稳定错误描述
         */
        private InvalidOrderCommandPayloadException(String message) {
            super(message);
        }

        /**
         * 创建保留根因类型但不持久化根因正文的载荷异常。
         *
         * @param message 稳定错误描述
         * @param cause 解析异常根因
         */
        private InvalidOrderCommandPayloadException(String message, Throwable cause) {
            super(message, cause);
        }
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
     * 读取同一用户分片内已经创建的订单。
     *
     * @param reservationId 稳定购票受理标识，同时也是订单号
     * @param userId 用户分片键
     * @return 已创建订单，不存在时返回 null
     */
    private OrderDO findByOrderSn(String reservationId, String userId) {
        return orderMapper.selectOne(Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getUserId, userId)
                .eq(OrderDO::getOrderSn, reservationId));
    }

    /**
     * 按用户分片键和稳定预订标识读取命令记录。
     *
     * @param reservationId 稳定购票受理标识
     * @param userId 订单所属用户标识
     * @return 命令记录，不存在时返回 null
     */
    private OrderCommandDO findOrderCommand(String reservationId, String userId) {
        return orderCommandMapper.selectOne(Wrappers.<OrderCommandDO>query()
                // 状态和幂等查询不需要恢复载荷，避免无谓解密并加载乘客敏感信息。
                .select("reservation_id", "user_id", "request_fingerprint", "status")
                .eq("user_id", userId)
                .eq("reservation_id", reservationId));
    }

    /**
     * 校验重复预订仍绑定同一用户和不可变参数。
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
        if (!Objects.equals(existing.getRequestFingerprint(), requestFingerprint)
                || !Objects.equals(existing.getUserId(), String.valueOf(requestParam.getUserId()))) {
            throw new ServiceException("订单命令标识与原请求不一致");
        }
        return existing.getOrderSn();
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
