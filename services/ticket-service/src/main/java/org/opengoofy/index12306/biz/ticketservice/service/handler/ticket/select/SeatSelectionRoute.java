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
 * 一次请求在共享状态机评估后得到的选座通道路由。
 *
 * @param useSingleChannel 是否走车厢区间锁单通道
 * @param sampleType 乐观占位结果应写入的样本类型；单通道时为空
 */
public record SeatSelectionRoute(boolean useSingleChannel, SeatSelectionSampleType sampleType) {

    /**
     * 创建单通道路由。
     *
     * @return 不会写入乐观占位统计的单通道路由
     */
    public static SeatSelectionRoute singleChannel() {
        // 单通道不执行 Redis 乐观占位，因此不存在可上报的冲突样本类型。
        return new SeatSelectionRoute(true, null);
    }

    /**
     * 创建乐观占位路由。
     *
     * @param sampleType 常态或探测样本类型
     * @return 需要记录 Lua 占位结果的乐观通道路由
     */
    public static SeatSelectionRoute optimistic(SeatSelectionSampleType sampleType) {
        // 调用方必须保留该样本类型直到 Lua 占位结果返回，避免状态在请求中途变化后误分类。
        return new SeatSelectionRoute(false, sampleType);
    }
}
