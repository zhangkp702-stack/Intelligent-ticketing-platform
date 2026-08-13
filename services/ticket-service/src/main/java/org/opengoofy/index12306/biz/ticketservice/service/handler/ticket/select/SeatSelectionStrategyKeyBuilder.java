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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select;

import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil;

/**
 * 构造跨实例共享的座位策略库存维度。
 */
public final class SeatSelectionStrategyKeyBuilder {

    private SeatSelectionStrategyKeyBuilder() {
    }

    /**
     * 根据车次、始发日期、乘车区间和座位类型生成稳定维度。
     *
     * @param requestParam 已校验的购票请求
     * @param seatType 座位类型
     * @return 可放入 Redis Cluster Hash Tag 的库存维度
     */
    public static String build(PurchaseTicketReqDTO requestParam, Integer seatType) {
        // 始发日期必须参与维度，避免同一车次不同开行日共享热点状态。
        return ServiceDateKeyUtil.buildKey(requestParam.getTrainId(), requestParam.getServiceDate(),
                requestParam.getDeparture(), requestParam.getArrival(), String.valueOf(seatType));
    }
}
