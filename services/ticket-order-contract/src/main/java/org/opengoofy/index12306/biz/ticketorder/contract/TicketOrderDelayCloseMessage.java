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
 * 延迟关单的跨服务消息协议。
 *
 * <p>keys、message、uuid 和 timestamp 与既有包装消息保持同名，
 * 使已经在 RocketMQ 中的旧延迟消息仍可被票务消费者反序列化。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderDelayCloseMessage {

    /** CDC Outbox 的稳定事件标识。 */
    private String messageId;

    /** 跨服务协议版本。 */
    private Long messageVersion;

    /** 订单创建 Command 标识，用于 Relay 校验和幂等关联。 */
    private String commandId;

    /** RocketMQ 业务键，固定为订单号。 */
    private String keys;

    /** 待延迟处理的订单事件。 */
    private TicketOrderDelayCloseEvent message;

    /** 与既有包装器兼容的稳定消息标识。 */
    private String uuid;

    /** 与既有包装器兼容的消息时间戳。 */
    private Long timestamp;
}
