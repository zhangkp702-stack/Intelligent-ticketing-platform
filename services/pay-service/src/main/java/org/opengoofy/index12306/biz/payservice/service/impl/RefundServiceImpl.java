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

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.payservice.common.enums.TradeStatusEnum;
import org.opengoofy.index12306.biz.payservice.convert.RefundRequestConvert;
import org.opengoofy.index12306.biz.payservice.dao.entity.PayDO;
import org.opengoofy.index12306.biz.payservice.dao.entity.RefundDO;
import org.opengoofy.index12306.biz.payservice.dao.mapper.PayMapper;
import org.opengoofy.index12306.biz.payservice.dao.mapper.RefundMapper;
import org.opengoofy.index12306.biz.payservice.dto.RefundCommand;
import org.opengoofy.index12306.biz.payservice.dto.RefundCreateDTO;
import org.opengoofy.index12306.biz.payservice.dto.RefundReqDTO;
import org.opengoofy.index12306.biz.payservice.dto.RefundRespDTO;
import org.opengoofy.index12306.biz.payservice.dto.base.RefundRequest;
import org.opengoofy.index12306.biz.payservice.dto.base.RefundResponse;
import org.opengoofy.index12306.biz.payservice.handler.AliRefundNativeHandler;
import org.opengoofy.index12306.biz.payservice.mq.event.RefundResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.payservice.mq.produce.RefundResultCallbackOrderSendProduce;
import org.opengoofy.index12306.biz.payservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.payservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.payservice.service.RefundService;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;


/**
 * 退款接口层实现
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {

    private final PayMapper payMapper;
    private final RefundMapper refundMapper;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final AbstractStrategyChoose abstractStrategyChoose;
    private final RefundResultCallbackOrderSendProduce refundResultCallbackOrderSendProduce;

    /**
     * 按退款请求标识幂等执行支付渠道退款并返回可追踪结果。
     *
     * @param requestParam 退款金额、范围和请求标识
     * @return 已存在或本次创建的退款结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RefundRespDTO commonRefund(RefundReqDTO requestParam) {
        if (requestParam.getRequestId() == null || requestParam.getRequestId().isBlank()) {
            throw new ServiceException("退款请求标识不能为空");
        }

        // 相同请求已经落库时直接返回原结果，避免再次调用第三方退款渠道。
        List<RefundDO> existingRefunds = findRefunds(requestParam.getRequestId());
        if (!existingRefunds.isEmpty()) {
            return buildRefundResult(requestParam, existingRefunds);
        }
        // 锁定同一订单的支付单，串行计算剩余可退金额，防止不同请求并发超退。
        LambdaQueryWrapper<PayDO> queryWrapper = Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getOrderSn, requestParam.getOrderSn())
                .last("FOR UPDATE");
        PayDO payDO = payMapper.selectOne(queryWrapper);
        if (Objects.isNull(payDO)) {
            log.error("支付单不存在，orderSn：{}", requestParam.getOrderSn());
            throw new ServiceException("支付单不存在");
        }
        // 等待行锁期间相同请求可能已经完成，获得锁后再次读取并复用原退款结果。
        existingRefunds = findRefunds(requestParam.getRequestId());
        if (!existingRefunds.isEmpty()) {
            return buildRefundResult(requestParam, existingRefunds);
        }
        int currentPayAmount = payDO.getPayAmount() == null ? payDO.getTotalAmount() : payDO.getPayAmount();
        if (requestParam.getRefundAmount() == null
                || requestParam.getRefundAmount() <= 0
                || requestParam.getRefundAmount() > currentPayAmount) {
            throw new ServiceException("退款金额超出当前可退金额");
        }
        payDO.setPayAmount(currentPayAmount - requestParam.getRefundAmount());

        // 先按请求标识创建退款明细，后续更新只影响本次选择的车票。
        RefundCreateDTO refundCreateDTO = BeanUtil.convert(requestParam, RefundCreateDTO.class);
        refundCreateDTO.setPaySn(payDO.getPaySn());
        createRefund(refundCreateDTO);
        /**
         * {@link AliRefundNativeHandler}
         */
        // 策略模式：通过策略模式封装退款渠道和退款场景，用户退款时动态选择对应的退款组件
        RefundCommand refundCommand = BeanUtil.convert(payDO, RefundCommand.class);
        refundCommand.setPayAmount(new BigDecimal(requestParam.getRefundAmount()));
        RefundRequest refundRequest = RefundRequestConvert.command2RefundRequest(refundCommand);
        RefundResponse result = abstractStrategyChoose.chooseAndExecuteResp(refundRequest.buildMark(), refundRequest);
        if (result == null || result.getStatus() == null) {
            throw new ServiceException("退款渠道未返回有效结果");
        }
        payDO.setStatus(result.getStatus());
        LambdaUpdateWrapper<PayDO> updateWrapper = Wrappers.lambdaUpdate(PayDO.class)
                .eq(PayDO::getOrderSn, requestParam.getOrderSn());
        int updateResult = payMapper.update(payDO, updateWrapper);
        if (updateResult <= 0) {
            log.error("修改支付单退款结果失败，支付单信息：{}", JSON.toJSONString(payDO));
            throw new ServiceException("修改支付单退款结果失败");
        }
        LambdaUpdateWrapper<RefundDO> refundUpdateWrapper = Wrappers.lambdaUpdate(RefundDO.class)
                .eq(RefundDO::getRefundRequestId, requestParam.getRequestId());
        RefundDO refundDO = new RefundDO();
        refundDO.setTradeNo(result.getTradeNo());
        refundDO.setStatus(result.getStatus());
        int refundUpdateResult = refundMapper.update(refundDO, refundUpdateWrapper);
        if (refundUpdateResult <= 0) {
            log.error("修改退款单退款结果失败，退款单信息：{}", JSON.toJSONString(refundDO));
            throw new ServiceException("修改退款单退款结果失败");
        }
        // 退款成功，回调订单服务告知退款结果，修改订单流转状态
        if (Objects.equals(result.getStatus(), TradeStatusEnum.TRADE_CLOSED.tradeCode())) {
            RefundResultCallbackOrderEvent refundResultCallbackOrderEvent = RefundResultCallbackOrderEvent.builder()
                    .orderSn(requestParam.getOrderSn())
                    .refundTypeEnum(requestParam.getRefundTypeEnum())
                    .partialRefundTicketDetailList(requestParam.getRefundDetailReqDTOList())
                    .build();
            refundResultCallbackOrderSendProduce.sendMessage(refundResultCallbackOrderEvent);
        }

        // 返回稳定字段供票务服务和后续智能体执行状态持久化。
        RefundRespDTO response = new RefundRespDTO();
        response.setRequestId(requestParam.getRequestId());
        response.setOrderSn(requestParam.getOrderSn());
        response.setRefundAmount(requestParam.getRefundAmount());
        response.setStatus(result.getStatus());
        response.setTradeNo(result.getTradeNo());
        return response;
    }

    /**
     * 为本次退款选择的每张车票创建可独立追踪的退款记录。
     *
     * @param requestParam 退款记录创建参数
     */
    private void createRefund(RefundCreateDTO requestParam) {
        // 订单详情只用于补齐退款审计字段，支付服务本身不向终端用户暴露该内部接口。
        Result<TicketOrderDetailRespDTO> queryTicketResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(requestParam.getOrderSn());
        if (!queryTicketResult.isSuccess() || Objects.isNull(queryTicketResult.getData())) {
            throw new ServiceException("车票订单不存在");
        }
        TicketOrderDetailRespDTO orderDetailRespDTO = queryTicketResult.getData();
        requestParam.getRefundDetailReqDTOList().forEach(each -> {
            RefundDO refundDO = new RefundDO();
            refundDO.setRefundRequestId(requestParam.getRequestId());
            refundDO.setPaySn(requestParam.getPaySn());
            refundDO.setOrderSn(requestParam.getOrderSn());
            refundDO.setTrainId(orderDetailRespDTO.getTrainId());
            refundDO.setTrainNumber(orderDetailRespDTO.getTrainNumber());
            refundDO.setDeparture(orderDetailRespDTO.getDeparture());
            refundDO.setArrival(orderDetailRespDTO.getArrival());
            refundDO.setDepartureTime(orderDetailRespDTO.getDepartureTime());
            refundDO.setArrivalTime(orderDetailRespDTO.getArrivalTime());
            refundDO.setRidingDate(orderDetailRespDTO.getRidingDate());
            refundDO.setSeatType(each.getSeatType());
            refundDO.setIdType(each.getIdType());
            refundDO.setIdCard(each.getIdCard());
            refundDO.setRealName(each.getRealName());
            refundDO.setRefundTime(new Date());
            refundDO.setAmount(each.getAmount());
            refundDO.setUserId(each.getUserId());
            refundDO.setUsername(each.getUsername());
            refundMapper.insert(refundDO);
        });
    }

    /**
     * 查询同一幂等请求已经创建的退款记录。
     *
     * @param requestId 退款请求标识
     * @return 已存在退款记录
     */
    private List<RefundDO> findRefunds(String requestId) {
        // 唯一索引和查询条件共同保证同一请求不会重复插入相同乘车人退款明细。
        LambdaQueryWrapper<RefundDO> queryWrapper = Wrappers.lambdaQuery(RefundDO.class)
                .eq(RefundDO::getRefundRequestId, requestId);
        return refundMapper.selectList(queryWrapper);
    }

    /**
     * 把已存在退款记录聚合为幂等响应。
     *
     * @param requestParam 当前退款请求
     * @param refunds 已存在退款明细
     * @return 原退款请求的可追踪结果
     */
    private RefundRespDTO buildRefundResult(RefundReqDTO requestParam, List<RefundDO> refunds) {
        // 同一请求下所有明细共享交易凭证和状态，金额按明细求和恢复。
        RefundDO first = refunds.get(0);
        RefundRespDTO response = new RefundRespDTO();
        response.setRequestId(requestParam.getRequestId());
        response.setOrderSn(requestParam.getOrderSn());
        response.setRefundAmount(refunds.stream()
                .map(RefundDO::getAmount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum());
        response.setStatus(first.getStatus());
        response.setTradeNo(first.getTradeNo());
        return response;
    }
}
