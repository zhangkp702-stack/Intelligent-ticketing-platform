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

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 为携带 Agent 操作标识的 V2 购票请求提供数据库级幂等保护。
 */
@Service
@RequiredArgsConstructor
public class PurchaseOperationService {

    private static final String PURCHASE_OPERATION_TYPE = "PURCHASE_TICKET";

    private final TicketService ticketService;
    private final BusinessOperationCoordinator operationCoordinator;

    /**
     * 执行 V2 购票；普通请求直接执行，Agent 请求先持久化认领操作标识。
     *
     * @param requestParam 购票参数和可选操作标识
     * @return 新建订单，或者重复成功操作已经保存的原订单结果
     */
    public TicketPurchaseRespDTO purchaseTicketsV2(PurchaseTicketReqDTO requestParam) {
        if (requestParam == null) {
            throw new ServiceException("购票请求不能为空");
        }
        String operationId = operationCoordinator.normalizeOperationId(requestParam.getOperationId());
        if (operationId == null) {
            // 浏览器等普通调用方未提供操作标识时保持原有 V2 接口语义。
            return ticketService.purchaseTicketsV2(requestParam);
        }

        // 通用协调器统一完成操作认领、参数绑定和成功结果重放。
        PurchaseFingerprintPayload fingerprintPayload = new PurchaseFingerprintPayload(
                requestParam.getTrainId(),
                requestParam.getDepartureDate(),
                requestParam.getPassengers(),
                requestParam.getChooseSeats(),
                requestParam.getDeparture(),
                requestParam.getArrival());
        return operationCoordinator.execute(
                operationId,
                PURCHASE_OPERATION_TYPE,
                fingerprintPayload,
                null,
                TicketPurchaseRespDTO.class,
                () -> ticketService.purchaseTicketsV2(requestParam));
    }

    /**
     * 用于生成购票业务参数摘要的不可变数据。
     *
     * @param trainId 车次标识
     * @param departureDate 乘车日期
     * @param passengers 乘车人与席别
     * @param chooseSeats 选座偏好
     * @param departure 出发站
     * @param arrival 到达站
     */
    private record PurchaseFingerprintPayload(
            String trainId,
            Date departureDate,
            List<PurchaseTicketPassengerDetailDTO> passengers,
            List<String> chooseSeats,
            String departure,
            String arrival) {
    }
}
