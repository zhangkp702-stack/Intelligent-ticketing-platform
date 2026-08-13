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
 * 最近一段时间内的 Redis Lua 座位占位统计。
 *
 * @param attempts 占位尝试数
 * @param conflicts 因目标座位已被占用产生的冲突数
 * @param reservations 参与统计的 reservation 近似去重数
 */
public record SeatConflictStatistics(long attempts, long conflicts, long reservations) {

    /**
     * 计算当前统计窗口的真实占位冲突率。
     *
     * @return 无样本时返回零，否则返回冲突数与尝试数之比
     */
    public double conflictRate() {
        // 空窗口不应被解释为百分之百成功，决策前还会校验最小样本量。
        return attempts == 0L ? 0D : (double) conflicts / attempts;
    }
}
