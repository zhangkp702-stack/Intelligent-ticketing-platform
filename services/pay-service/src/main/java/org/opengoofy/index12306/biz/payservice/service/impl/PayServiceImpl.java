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

package org.opengoofy.index12306.biz.payservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.payservice.common.enums.PayChannelEnum;
import org.opengoofy.index12306.biz.payservice.common.enums.TradeStatusEnum;
import org.opengoofy.index12306.biz.payservice.dao.entity.PayDO;
import org.opengoofy.index12306.biz.payservice.dao.mapper.PayMapper;
import org.opengoofy.index12306.biz.payservice.dto.BalancePayReqDTO;
import org.opengoofy.index12306.biz.payservice.dto.BalancePayRespDTO;
import org.opengoofy.index12306.biz.payservice.dto.PayInfoRespDTO;
import org.opengoofy.index12306.biz.payservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.payservice.remote.UserBalanceRemoteService;
import org.opengoofy.index12306.biz.payservice.remote.dto.BalanceChangeReqDTO;
import org.opengoofy.index12306.biz.payservice.remote.dto.BalancePaymentConfirmReqDTO;
import org.opengoofy.index12306.biz.payservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.payservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.payservice.remote.dto.UserBalanceRespDTO;
import org.opengoofy.index12306.biz.payservice.service.PayService;
import org.opengoofy.index12306.biz.payservice.service.payid.PayIdGeneratorManager;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 支付接口层实现
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayServiceImpl implements PayService {

    private static final int PENDING_PAYMENT_ORDER_STATUS = 0;
    private static final int PAID_ORDER_STATUS = 10;
    private static final int BALANCE_TRADE_TYPE = 0;

    private final PayMapper payMapper;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final UserBalanceRemoteService userBalanceRemoteService;

    /**
     * 校验当前用户订单并使用站内余额完成支付。
     *
     * @param requestParam 待支付订单号
     * @return 支付流水、金额、余额和状态
     */
    @Idempotent(
            type = IdempotentTypeEnum.SPEL,
            uniqueKeyPrefix = "index12306-pay:lock_create_pay:",
            key = "#requestParam.getOrderSn()"
    )
    @Transactional(rollbackFor = Exception.class)
    @Override
    public BalancePayRespDTO balancePay(BalancePayReqDTO requestParam) {
        // 订单服务负责最终归属校验，订单号本身不能作为支付授权凭证。
        TicketOrderDetailRespDTO orderDetail = requireSelfOrder(requestParam.getOrderSn());
        PayDO payDO = findPayByOrderSn(requestParam.getOrderSn());
        if (isPaid(payDO)) {
            // 重复确认支付时直接复用原支付单和当前余额，不再次执行扣款。
            return buildPayResponse(payDO, requireCurrentBalance());
        }
        if (Objects.equals(orderDetail.getStatus(), PAID_ORDER_STATUS)
                && (payDO == null
                || !Objects.equals(payDO.getChannel(), PayChannelEnum.BALANCE.getCode()))) {
            throw new ServiceException("订单已经通过其他支付记录完成支付");
        }
        if (!Objects.equals(orderDetail.getStatus(), PENDING_PAYMENT_ORDER_STATUS)
                && !Objects.equals(orderDetail.getStatus(), PAID_ORDER_STATUS)) {
            throw new ServiceException("当前订单状态不允许支付");
        }

        // 金额完全来自订单明细，避免客户端或模型修改应付金额。
        int totalAmount = calculateOrderAmount(orderDetail.getPassengerDetails());
        if (payDO == null) {
            payDO = createPendingPay(orderDetail, totalAmount);
        } else if (!Objects.equals(payDO.getTotalAmount(), totalAmount)) {
            throw new ServiceException("支付单金额与订单金额不一致");
        }

        // 用户服务使用订单号作为幂等号，即使本地事务重试也不会重复扣除余额。
        Result<UserBalanceRespDTO> debitResult = userBalanceRemoteService.debit(
                new BalanceChangeReqDTO(requestParam.getOrderSn(), (long) totalAmount));
        if (!debitResult.isSuccess() || debitResult.getData() == null) {
            throw new ServiceException("账户余额扣款失败");
        }

        // 扣款成功后在本地记录支付结果，随后复用现有订单支付成功事件。
        payDO.setTradeNo("BALANCE_" + payDO.getPaySn());
        payDO.setStatus(TradeStatusEnum.TRADE_SUCCESS.tradeCode());
        payDO.setPayAmount(totalAmount);
        payDO.setGmtPayment(new Date());
        LambdaUpdateWrapper<PayDO> updateWrapper = Wrappers.lambdaUpdate(PayDO.class)
                .eq(PayDO::getOrderSn, requestParam.getOrderSn());
        if (payMapper.update(payDO, updateWrapper) != 1) {
            throw new ServiceException("修改支付单支付结果失败");
        }
        // 同步确认订单状态，避免余额支付依赖外部支付回调或 RocketMQ 可用性。
        Result<Boolean> confirmResult = ticketOrderRemoteService.confirmBalancePayment(
                new BalancePaymentConfirmReqDTO(
                        requestParam.getOrderSn(),
                        PayChannelEnum.BALANCE.getCode(),
                        payDO.getGmtPayment()));
        if (!confirmResult.isSuccess() || !Boolean.TRUE.equals(confirmResult.getData())) {
            throw new ServiceException("确认订单支付状态失败，请稍后重试");
        }
        return buildPayResponse(payDO, debitResult.getData().getBalance());
    }

    /**
     * 按订单号查询当前用户自己的支付单。
     *
     * @param orderSn 订单号
     * @return 支付单详情
     */
    @Override
    public PayInfoRespDTO getPayInfoByOrderSn(String orderSn) {
        // 查询前再次校验订单归属，避免根据可猜测订单号读取他人支付信息。
        requireSelfOrder(orderSn);
        PayDO payDO = findPayByOrderSn(orderSn);
        return BeanUtil.convert(payDO, PayInfoRespDTO.class);
    }

    /**
     * 按支付流水号查询当前用户自己的支付单。
     *
     * @param paySn 支付流水号
     * @return 支付单详情
     */
    @Override
    public PayInfoRespDTO getPayInfoByPaySn(String paySn) {
        LambdaQueryWrapper<PayDO> queryWrapper = Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getPaySn, paySn);
        PayDO payDO = payMapper.selectOne(queryWrapper);
        if (payDO != null) {
            // 支付流水查询同样以关联订单的归属校验作为授权依据。
            requireSelfOrder(payDO.getOrderSn());
        }
        return BeanUtil.convert(payDO, PayInfoRespDTO.class);
    }

    /**
     * 查询并校验当前用户的订单详情。
     *
     * @param orderSn 订单号
     * @return 已通过归属校验的订单详情
     */
    private TicketOrderDetailRespDTO requireSelfOrder(String orderSn) {
        // Feign 拦截器会把当前用户身份传给订单服务完成归属校验。
        Result<TicketOrderDetailRespDTO> orderResult =
                ticketOrderRemoteService.querySelfTicketOrderByOrderSn(orderSn);
        if (!orderResult.isSuccess() || orderResult.getData() == null) {
            throw new ServiceException("订单不存在或无权访问");
        }
        return orderResult.getData();
    }

    /**
     * 汇总订单内全部乘车人的真实票价。
     *
     * @param passengerDetails 订单乘车人明细
     * @return 订单总金额，单位为分
     */
    private int calculateOrderAmount(List<TicketOrderPassengerDetailRespDTO> passengerDetails) {
        // 空订单和非正金额订单不能进入余额扣款流程。
        if (passengerDetails == null || passengerDetails.isEmpty()) {
            throw new ServiceException("订单没有可支付的车票");
        }
        long totalAmount = passengerDetails.stream()
                .map(TicketOrderPassengerDetailRespDTO::getAmount)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
        if (totalAmount <= 0 || totalAmount > Integer.MAX_VALUE) {
            throw new ServiceException("订单金额无效");
        }
        return (int) totalAmount;
    }

    /**
     * 为订单创建一条待支付的站内余额支付单。
     *
     * @param orderDetail 订单详情
     * @param totalAmount 订单金额
     * @return 新建支付单
     */
    private PayDO createPendingPay(TicketOrderDetailRespDTO orderDetail, int totalAmount) {
        // 支付渠道、标题和金额全部由服务端确定，避免外部请求篡改。
        PayDO payDO = new PayDO();
        payDO.setPaySn(PayIdGeneratorManager.generateId(orderDetail.getOrderSn()));
        payDO.setOrderSn(orderDetail.getOrderSn());
        payDO.setOutOrderSn(orderDetail.getOrderSn());
        payDO.setChannel(PayChannelEnum.BALANCE.getCode());
        payDO.setTradeType(BALANCE_TRADE_TYPE);
        payDO.setSubject(orderDetail.getDeparture() + "-" + orderDetail.getArrival());
        payDO.setOrderRequestId(orderDetail.getOrderSn());
        payDO.setTotalAmount(totalAmount);
        payDO.setPayAmount(0);
        payDO.setStatus(TradeStatusEnum.WAIT_BUYER_PAY.tradeCode());
        if (payMapper.insert(payDO) != 1) {
            throw new ServiceException("支付单创建失败");
        }
        return payDO;
    }

    /**
     * 根据订单号查询支付单。
     *
     * @param orderSn 订单号
     * @return 支付单，不存在时返回 null
     */
    private PayDO findPayByOrderSn(String orderSn) {
        // orderSn 是支付表分片键，查询只会命中一个支付分片。
        return payMapper.selectOne(Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getOrderSn, orderSn));
    }

    /**
     * 判断支付单是否已经完成。
     *
     * @param payDO 支付单
     * @return 已支付时返回 true
     */
    private boolean isPaid(PayDO payDO) {
        // 已成功支付的订单重复提交时只返回原结果。
        return payDO != null
                && Objects.equals(payDO.getStatus(), TradeStatusEnum.TRADE_SUCCESS.tradeCode());
    }

    /**
     * 查询当前用户余额并校验远程响应。
     *
     * @return 当前余额
     */
    private Long requireCurrentBalance() {
        // 幂等支付响应展示实时余额，不根据旧支付单推算。
        Result<UserBalanceRespDTO> balanceResult = userBalanceRemoteService.queryBalance();
        if (!balanceResult.isSuccess() || balanceResult.getData() == null) {
            throw new ServiceException("账户余额查询失败");
        }
        return balanceResult.getData().getBalance();
    }

    /**
     * 将支付单转换为前端需要的稳定响应。
     *
     * @param payDO 支付单
     * @param balance 当前余额
     * @return 余额支付结果
     */
    private BalancePayRespDTO buildPayResponse(PayDO payDO, Long balance) {
        // 响应只暴露支付结果，不返回内部用户身份或订单明细。
        return BalancePayRespDTO.builder()
                .orderSn(payDO.getOrderSn())
                .paySn(payDO.getPaySn())
                .amount(payDO.getPayAmount())
                .balance(balance)
                .status(payDO.getStatus())
                .build();
    }
}
