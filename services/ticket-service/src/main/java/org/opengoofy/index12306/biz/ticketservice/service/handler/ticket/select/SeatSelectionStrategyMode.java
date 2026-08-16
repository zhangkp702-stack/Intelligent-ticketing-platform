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
 * 跨 ticket-service 实例共享的选座策略状态。
 */
public enum SeatSelectionStrategyMode {

    /** 全量使用 Redis 乐观临时占位。 */
    OPTIMISTIC,

    /** 全量使用车厢区间锁单通道。 */
    SINGLE,

    /** 从单通道恢复前，仅向少量稳定哈希请求开放乐观占位。 */
    PROBING,

    /** 探测健康后按固定比例逐级扩大乐观占位流量。 */
    RECOVERING
}
