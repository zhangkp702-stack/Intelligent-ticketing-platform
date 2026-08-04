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

package org.opengoofy.index12306.biz.payservice.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 退款命令权威状态，只返回核对所需的安全字段。
 */
@Data
@Builder
public class RefundCommandStatusRespDTO {

    /**
     * 稳定退款命令标识。
     */
    private String commandId;

    /**
     * Agent 真实交易意图标识。
     */
    private String actionId;

    /**
     * 命令状态：NOT_FOUND、PROCESSING、SUCCEEDED、FAILED 或 UNKNOWN。
     */
    private String status;

    /**
     * 关联订单号。
     */
    private String orderSn;

    /**
     * 已确定的退款金额。
     */
    private Integer refundAmount;

    /**
     * 支付服务内部退款流水引用。
     */
}
