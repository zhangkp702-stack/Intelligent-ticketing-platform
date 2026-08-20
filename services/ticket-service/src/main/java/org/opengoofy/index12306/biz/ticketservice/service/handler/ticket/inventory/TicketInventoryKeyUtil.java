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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.inventory;

import org.opengoofy.index12306.biz.ticketservice.toolkit.ServiceDateKeyUtil;

import java.util.Date;
import java.util.Objects;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_INVENTORY;

/**
 * 统一余票库存 Redis 键构造工具。
 */
public final class TicketInventoryKeyUtil {

    private TicketInventoryKeyUtil() {
    }

    /**
     * 构造按车次和始发日期隔离且支持 Redis Cluster 同槽执行的库存键。
     *
     * @param trainId 车次标识
     * @param serviceDate 列车始发日期
     * @return 统一库存 Redis Hash 键
     */
    public static String buildInventoryKey(String trainId, Date serviceDate) {
        // 新库存不兼容缺少始发日期的历史键，避免不同运行日期共享同一份库存。
        Objects.requireNonNull(trainId, "trainId must not be null");
        Objects.requireNonNull(serviceDate, "serviceDate must not be null");
        return String.format(TRAIN_STATION_INVENTORY, trainId, ServiceDateKeyUtil.format(serviceDate));
    }

    /**
     * 构造统一库存 Hash 中的区间席别字段。
     *
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 席别类型
     * @return departure_arrival_seatType 格式的 Hash 字段
     */
    public static String buildInventoryField(String departure, String arrival, Integer seatType) {
        // Field 继续沿用旧令牌桶格式，后续影子对比无需转换历史区间语义。
        Objects.requireNonNull(departure, "departure must not be null");
        Objects.requireNonNull(arrival, "arrival must not be null");
        Objects.requireNonNull(seatType, "seatType must not be null");
        return String.join("_", departure, arrival, String.valueOf(seatType));
    }
}
