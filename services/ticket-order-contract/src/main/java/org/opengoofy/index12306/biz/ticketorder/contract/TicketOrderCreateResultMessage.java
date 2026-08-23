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
 * 订单服务返回给票务服务的订单创建结果。
 *
 * <p>结果只表达已确定的成功或失败；处理中状态由 Inbox/Outbox 重试和查询接口表达。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderCreateResultMessage {

    /** 结果消息唯一标识。 */
    private String messageId;

    /** 协议版本。 */
    private Long messageVersion;

    /** 对应的命令消息标识。 */
    private String sourceMessageId;

    /** 购票受理标识。 */
    private String reservationId;

    /** 稳定命令标识。 */
    private String commandId;

    /** 结果状态，只允许 SUCCEEDED 或 FAILED。 */
    private String status;

    /** 成功时的订单号。 */
    private String orderSn;

    /** 失败时的稳定错误分类。 */
    private String failureCategory;
}
