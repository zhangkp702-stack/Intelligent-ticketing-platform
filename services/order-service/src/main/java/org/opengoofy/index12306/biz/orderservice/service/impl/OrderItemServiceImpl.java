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

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCanalErrorCodeEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderItemMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderMapper;
import org.opengoofy.index12306.biz.orderservice.dto.domain.OrderItemStatusReversalDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.TicketOrderItemQueryReqDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.service.OrderItemService;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;

/**
 * 订单明细接口层实现
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Service
@RequiredArgsConstructor
public class OrderItemServiceImpl extends ServiceImpl<OrderItemMapper, OrderItemDO> implements OrderItemService {

    private final OrderMapper orderMapper;

    private final OrderItemMapper orderItemMapper;

    /**
     * 按期望状态原子更新订单及指定子订单，防止退款回调覆盖其他状态迁移。
     *
     * @param requestParam 包含目标状态、期望原状态和待退款子订单的参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void orderItemStatusReversal(OrderItemStatusReversalDTO requestParam) {
        // 父订单只能从支付完成或已部分退款状态进入退款态，条件更新承担并发裁决。
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setStatus(requestParam.getOrderStatus());
        int orderUpdateResult = orderMapper.update(updateOrderDO, Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn())
                .in(OrderDO::getStatus, requestParam.getExpectedOrderStatuses()));
        if (orderUpdateResult <= 0) {
            // 部分退款后的后续部分退款不改变父订单状态，确认仍处于目标状态后继续更新不同子订单。
            OrderDO currentOrder = orderMapper.selectOne(Wrappers.lambdaQuery(OrderDO.class)
                    .eq(OrderDO::getOrderSn, requestParam.getOrderSn())
                    .select(OrderDO::getStatus));
            if (currentOrder == null
                    || !Objects.equals(currentOrder.getStatus(), requestParam.getOrderStatus())
                    || !requestParam.getExpectedOrderStatuses().contains(requestParam.getOrderStatus())) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
            }
        }

        // 仅更新回调中指定且仍处于支付完成状态的子订单，避免重复退款修改已退款记录。
        if (CollectionUtil.isNotEmpty(requestParam.getOrderItemDOList())) {
            List<OrderItemDO> orderItemDOList = requestParam.getOrderItemDOList();
            orderItemDOList.forEach(o -> {
                OrderItemDO orderItemDO = new OrderItemDO();
                orderItemDO.setStatus(requestParam.getOrderItemStatus());
                int orderItemUpdateResult = orderItemMapper.update(orderItemDO, Wrappers.lambdaUpdate(OrderItemDO.class)
                        .eq(OrderItemDO::getOrderSn, requestParam.getOrderSn())
                        .eq(OrderItemDO::getRealName, o.getRealName())
                        .eq(OrderItemDO::getStatus, requestParam.getExpectedOrderItemStatus()));
                if (orderItemUpdateResult <= 0) {
                    throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_ITEM_STATUS_REVERSAL_ERROR);
                }
            });
        }
    }

    /**
     * 查询当前登录用户订单中指定的子订单记录。
     *
     * @param requestParam 订单号和子订单记录标识
     * @return 当前用户可访问的子订单明细
     */
    @Override
    public List<TicketOrderPassengerDetailRespDTO> queryTicketItemOrderById(TicketOrderItemQueryReqDTO requestParam) {
        // 先校验订单归属，避免仅凭订单号和子订单标识查询他人的乘车信息。
        OrderDO orderDO = orderMapper.selectOne(Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn()));
        if (orderDO == null || UserContext.getUserId() == null
                || !UserContext.getUserId().equals(orderDO.getUserId())) {
            throw new ClientException("订单不存在或无权访问");
        }
        // 归属校验通过后，再按订单范围查询选中的子订单明细。
        LambdaQueryWrapper<OrderItemDO> queryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, requestParam.getOrderSn())
                .in(OrderItemDO::getId, requestParam.getOrderItemRecordIds());
        List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(queryWrapper);
        return BeanUtil.convert(orderItemDOList, TicketOrderPassengerDetailRespDTO.class);
    }
}
