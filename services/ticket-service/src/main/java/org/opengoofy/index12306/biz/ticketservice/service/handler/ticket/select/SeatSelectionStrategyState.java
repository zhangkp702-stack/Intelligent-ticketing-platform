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

/**
 * Redis Lua 返回的共享选座策略状态快照。
 *
 * @param mode 当前策略模式
 * @param version 状态迁移版本
 * @param enteredAtMillis 进入当前模式的时间
 * @param lastEvaluatedAtMillis 最近一次有效评估时间
 * @param optimisticPercentage 当前允许乐观占位的稳定哈希比例
 * @param healthyPeriods 当前恢复级别已连续满足的健康周期数
 * @param reason 最近一次状态迁移原因
 */
public record SeatSelectionStrategyState(SeatSelectionStrategyMode mode,
                                         long version,
                                         long enteredAtMillis,
                                         long lastEvaluatedAtMillis,
                                         int optimisticPercentage,
                                         int healthyPeriods,
                                         String reason) {

    /**
     * 创建尚未写入 Redis 前的默认乐观状态。
     *
     * @return 初始乐观状态
     */
    public static SeatSelectionStrategyState initial() {
        // 新库存维度没有历史冲突时默认乐观，Lua 会在首次评估时根据实时样本决定是否降级。
        return new SeatSelectionStrategyState(SeatSelectionStrategyMode.OPTIMISTIC, 0L, 0L, 0L, 100, 0, "initial");
    }
}
