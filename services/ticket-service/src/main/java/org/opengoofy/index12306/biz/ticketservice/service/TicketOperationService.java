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

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.RefundTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 为携带 Agent 操作标识的取消订单和退票请求提供数据库级幂等保护。
 */
@Service
@RequiredArgsConstructor
public class TicketOperationService {

    private static final String CANCELLATION_OPERATION_TYPE = "CANCEL_TICKET_ORDER";
    private static final String REFUND_OPERATION_TYPE = "REFUND_TICKET";

    private final TicketService ticketService;
    private final BusinessOperationCoordinator operationCoordinator;

    /**
     * 执行取消订单；普通请求直接执行，Agent 请求先持久化认领操作标识。
     *
     * @param requestParam 取消订单参数和可选操作标识
     */
    public void cancelTicketOrder(CancelTicketOrderReqDTO requestParam) {
        if (requestParam == null) {
            throw new ServiceException("取消订单请求不能为空");
        }
        String operationId = operationCoordinator.normalizeOperationId(requestParam.getOperationId());
        if (operationId == null) {
            // 普通调用方未提供操作标识时保持原有取消订单接口语义。
            ticketService.cancelTicketOrder(requestParam);
            return;
        }

        // 布尔结果只用于保存成功终态，重复成功请求不会再次释放订单和座位资源。
        operationCoordinator.execute(
                operationId,
                CANCELLATION_OPERATION_TYPE,
                new CancellationFingerprintPayload(requestParam.getOrderSn()),
                requestParam.getOrderSn(),
                Boolean.class,
                () -> {
                    ticketService.cancelTicketOrder(requestParam);
                    return Boolean.TRUE;
                });
    }

    /**
     * 执行车票退款；Agent 请求同时将操作标识作为支付退款幂等标识。
     *
     * @param requestParam 退票范围和可选操作标识
     * @return 首次退款结果，或重复成功操作保存的原结果
     */
    public RefundTicketRespDTO refundTicket(RefundTicketReqDTO requestParam) {
        if (requestParam == null) {
            throw new ServiceException("退票请求不能为空");
        }
        String operationId = operationCoordinator.normalizeOperationId(requestParam.getOperationId());
        if (operationId == null) {
            // 普通调用方继续使用原有退款请求标识生成和支付层幂等逻辑。
            return ticketService.commonTicketRefund(requestParam);
        }

        // Agent 的票务操作标识和支付退款请求标识必须一致，形成跨服务稳定幂等键。
        normalizeRefundRequestId(requestParam, operationId);
        List<String> canonicalItemIds = requestParam.getSubOrderRecordIdReqList() == null
                ? List.of()
                : requestParam.getSubOrderRecordIdReqList().stream().sorted().toList();
        RefundFingerprintPayload fingerprintPayload = new RefundFingerprintPayload(
                requestParam.getOrderSn(),
                requestParam.getType(),
                canonicalItemIds);
        return operationCoordinator.execute(
                operationId,
                REFUND_OPERATION_TYPE,
                fingerprintPayload,
                requestParam.getOrderSn(),
                RefundTicketRespDTO.class,
                () -> ticketService.commonTicketRefund(requestParam));
    }

    /**
     * 规范化并校验 Agent 退票使用的支付层请求标识。
     *
     * @param requestParam 退票请求
     * @param operationId 已规范化的 Agent 操作标识
     */
    private void normalizeRefundRequestId(
            RefundTicketReqDTO requestParam,
            String operationId) {
        if (StrUtil.isBlank(requestParam.getRequestId())) {
            // 下游支付服务复用同一操作标识，网络重放时仍能查询到原退款记录。
            requestParam.setRequestId(operationId);
            return;
        }
        if (!operationId.equals(requestParam.getRequestId().trim())) {
            throw new ServiceException("退票操作标识与退款请求标识不一致");
        }
        requestParam.setRequestId(operationId);
    }

    /**
     * 用于生成取消订单业务参数摘要的不可变数据。
     *
     * @param orderSn 订单号
     */
    private record CancellationFingerprintPayload(String orderSn) {
    }

    /**
     * 用于生成退票业务参数摘要的不可变数据。
     *
     * @param orderSn 订单号
     * @param type 退票类型
     * @param orderItemIds 规范排序后的子订单记录标识
     */
    private record RefundFingerprintPayload(
            String orderSn,
            Integer type,
            List<String> orderItemIds) {
    }
}
