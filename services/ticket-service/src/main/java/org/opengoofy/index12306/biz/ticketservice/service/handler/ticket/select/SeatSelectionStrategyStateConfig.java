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
 * Redis Lua 状态机迁移使用的固定阈值与时间边界。
 *
 * @param evaluationIntervalMillis 两次有效状态评估的最短间隔
 * @param minimumAttempts 统计窗口生效前的最少样本数
 * @param conflictRateThresholdBps 进入单通道的冲突率阈值，单位为万分比
 * @param recoveryConflictRateThresholdBps 恢复健康阈值，必须低于进入阈值
 * @param lowStockThreshold 样本不足时触发单通道的余票阈值
 * @param singleMinimumResidenceMillis 单通道最短驻留时间
 * @param probePercentage 初始探测流量比例
 * @param healthyPeriodsRequired 每一恢复级别需要连续满足的健康周期数
 */
public record SeatSelectionStrategyStateConfig(long evaluationIntervalMillis,
                                               int minimumAttempts,
                                               int conflictRateThresholdBps,
                                               int recoveryConflictRateThresholdBps,
                                               int lowStockThreshold,
                                               long singleMinimumResidenceMillis,
                                               int probePercentage,
                                               int healthyPeriodsRequired) {
}
