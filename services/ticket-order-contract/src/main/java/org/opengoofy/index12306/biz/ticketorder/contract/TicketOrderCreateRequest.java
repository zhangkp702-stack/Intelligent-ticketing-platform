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

package org.opengoofy.index12306.biz.ticketorder.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 可重放的订单创建请求快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderCreateRequest {

    /** 用户 ID。 */
    private String userId;

    /** 用户名。 */
    private String username;

    /** 车次 ID。 */
    private Long trainId;

    /** 出发站。 */
    private String departure;

    /** 到达站。 */
    private String arrival;

    /** 订单来源。 */
    private Integer source;

    /** 下单时间。 */
    private Date orderTime;

    /** 乘车日期。 */
    private Date ridingDate;

    /** 车次号。 */
    private String trainNumber;

    /** 出发时间。 */
    private Date departureTime;

    /** 到达时间。 */
    private Date arrivalTime;

    /** 订单明细。 */
    private List<TicketOrderCreateItem> ticketOrderItems;
}
