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

/**
 * 票务服务发送给订单服务的订单创建命令。
 *
 * <p>消息包含可重放的完整请求和稳定幂等标识，消费者不得依赖发送方线程上下文。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderCreateCommandMessage {

    /** 消息唯一标识，通常对应 Outbox eventId。 */
    private String messageId;

    /** 协议版本。 */
    private Long messageVersion;

    /** 购票受理标识，用于座位占用和结果回传。 */
    private String reservationId;

    /** 业务动作标识。 */
    private String actionId;

    /** 稳定命令标识，订单服务以此执行幂等。 */
    private String commandId;

    /** 可独立重放的建单请求。 */
    private TicketOrderCreateRequest request;
}
