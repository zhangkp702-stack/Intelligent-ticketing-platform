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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.purchase;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketPurchaseMetrics;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 购票流程过滤器之验证参数必填
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Component
@RequiredArgsConstructor
public class TrainPurchaseTicketParamNotNullChainHandler implements TrainPurchaseTicketChainFilter<PurchaseTicketReqDTO> {

    private final TicketPurchaseMetrics ticketPurchaseMetrics;

    /**
     * 校验购票请求和乘车人的必填字段，并记录纯内存参数校验耗时。
     *
     * @param requestParam 待校验的购票请求
     */
    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        Timer.Sample validationTimer = ticketPurchaseMetrics.startStageTimer();
        String validationResult = "failed";
        try {
            // 基础字段缺失时立即拒绝，避免无效请求进入缓存和库存链路。
            if (StrUtil.isBlank(requestParam.getTrainId())) {
                throw new ClientException("列车标识不能为空");
            }
            if (StrUtil.isBlank(requestParam.getDeparture())) {
                throw new ClientException("出发站点不能为空");
            }
            if (StrUtil.isBlank(requestParam.getArrival())) {
                throw new ClientException("到达站点不能为空");
            }
            if (CollUtil.isEmpty(requestParam.getPassengers())) {
                throw new ClientException("乘车人至少选择一位");
            }
            // 每个乘车人都必须携带服务端选座所需的乘车人和席别标识。
            for (PurchaseTicketPassengerDetailDTO each : requestParam.getPassengers()) {
                if (StrUtil.isBlank(each.getPassengerId())) {
                    throw new ClientException("乘车人不能为空");
                }
                if (Objects.isNull(each.getSeatType())) {
                    throw new ClientException("座位类型不能为空");
                }
            }
            validationResult = "success";
        } finally {
            // 失败样本同样需要落入固定标签，便于区分参数拒绝和下游慢请求。
            ticketPurchaseMetrics.recordStage(validationTimer, "param_not_null", validationResult);
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
