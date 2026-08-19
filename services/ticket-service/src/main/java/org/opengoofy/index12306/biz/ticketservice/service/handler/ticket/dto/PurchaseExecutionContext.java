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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto;

import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;

import java.util.List;

/**
 * 单次购票请求复用的静态执行上下文。
 *
 * @param requestParam 原始购票请求
 * @param train 当前车次启动预热快照
 * @param stationNames 当前车次有序站点快照
 * @param affectedRoutes 当前购票区间会影响的余票区间
 */
public record PurchaseExecutionContext(
        PurchaseTicketReqDTO requestParam,
        TrainDO train,
        List<String> stationNames,
        List<RouteDTO> affectedRoutes) {

    /**
     * 固化本次请求使用的列表快照，避免后续处理器修改共享预热数据。
     *
     * @param requestParam 原始购票请求
     * @param train 当前车次启动预热快照
     * @param stationNames 当前车次有序站点快照
     * @param affectedRoutes 当前购票区间会影响的余票区间
     */
    public PurchaseExecutionContext {
        // 列表使用不可变副本，责任链、令牌桶和锁座阶段只能读取同一份请求快照。
        stationNames = List.copyOf(stationNames);
        affectedRoutes = List.copyOf(affectedRoutes);
    }
}
