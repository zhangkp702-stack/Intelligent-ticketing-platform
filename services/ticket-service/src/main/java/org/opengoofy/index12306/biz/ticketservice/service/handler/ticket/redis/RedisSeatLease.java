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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.redis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis 临时占座租约的可重放上下文。
 *
 * <p>该对象与座位位图在同一 Lua 脚本中写入，扫描器可在 MySQL 尚未来得及保存座位快照时精确释放 owner。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedisSeatLease {

    /** 座位预订和 Redis owner 的稳定标识。 */
    private String reservationId;

    /** 列车标识。 */
    private String trainId;

    /** Redis 键使用的列车始发日期，格式为 yyyyMMdd。 */
    private String serviceDateKey;

    /** 出发站。 */
    private String departure;

    /** 到达站。 */
    private String arrival;

    /** 席别。 */
    private Integer seatType;

    /** 车厢号。 */
    private String carriageNumber;

    /** 车厢座位位图下标，使用逗号分隔。 */
    private String seatBits;
}
