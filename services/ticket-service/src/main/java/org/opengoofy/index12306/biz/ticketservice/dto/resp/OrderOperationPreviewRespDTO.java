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

package org.opengoofy.index12306.biz.ticketservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前用户订单操作预检查结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderOperationPreviewRespDTO {

    /**
     * 订单号
     */
    private String orderSn;

    /**
     * 当前订单状态
     */
    private Integer orderStatus;

    /**
     * 是否允许取消
     */
    private Boolean canCancel;

    /**
     * 是否允许支付
     */
    private Boolean canPay;

    /**
     * 是否允许退票
     */
    private Boolean canRefund;

    /**
     * 当前操作不可执行时的安全原因
     */
    private String reason;
}
