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

import java.util.List;

/**
 * 锁座事务外完成的乐观 Redis 座位临时占位结果。
 *
 * <p>该对象只表示 Redis owner 已经成功写入；数据库座位位图确认、车票写入和 reservation/Outbox
 * 持久化仍必须在同一个本地事务中完成。</p>
 *
 * @param selectedTickets 本次已经由 reservationId 持有的座位明细
 * @param optimisticRedisHold 是否可以直接进入数据库确认事务
 */
public record PreparedSeatSelection(
        List<TrainPurchaseTicketRespDTO> selectedTickets,
        boolean optimisticRedisHold) {

    /**
     * 固化已占位座位明细，避免调用方在事务开始前修改补偿范围。
     */
    public PreparedSeatSelection {
        selectedTickets = List.copyOf(selectedTickets);
    }

    /**
     * 创建无法使用乐观 Redis 路径时的回退标记。
     *
     * @return 由事务内既有单通道选座逻辑处理的标记
     */
    public static PreparedSeatSelection fallbackToTransactionalSelection() {
        return new PreparedSeatSelection(List.of(), false);
    }
}
