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

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.common.enums.TicketInventoryModeEnum;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Optional;

/**
 * 统一余票库存只读访问入口，第一阶段不接管既有查询、购票和回补链路。
 */
@Component
@RequiredArgsConstructor
public class TicketInventoryService {

    private final DistributedCache distributedCache;
    private final TicketInventoryProperties properties;

    /**
     * 返回当前统一库存迁移模式。
     *
     * @return legacy、shadow 或 unified 模式
     */
    public TicketInventoryModeEnum mode() {
        return properties.getMode();
    }

    /**
     * 从统一库存 Hash 查询指定区间和席别的数量，缓存尚未建立时返回空。
     *
     * @param trainId 车次标识
     * @param serviceDate 列车始发日期
     * @param departure 出发站
     * @param arrival 到达站
     * @param seatType 席别类型
     * @return 已存在的统一库存数量，未初始化时为空
     */
    public Optional<Integer> query(String trainId, Date serviceDate, String departure, String arrival, Integer seatType) {
        // 第一阶段只读取新 Hash，不触发数据库回源或修改现有两套库存。
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        Object quantity = stringRedisTemplate.opsForHash().get(
                TicketInventoryKeyUtil.buildInventoryKey(trainId, serviceDate),
                TicketInventoryKeyUtil.buildInventoryField(departure, arrival, seatType));
        return Optional.ofNullable(quantity).map(Object::toString).map(Integer::parseInt);
    }
}
