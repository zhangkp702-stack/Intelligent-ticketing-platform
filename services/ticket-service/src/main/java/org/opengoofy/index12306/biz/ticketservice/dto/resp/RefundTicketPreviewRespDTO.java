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

import java.util.List;

/**
 * 退票执行前的只读预览结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundTicketPreviewRespDTO {

    /**
     * 订单号
     */
    private String orderSn;

    /**
     * 退款类型
     */
    private Integer type;

    /**
     * 是否允许按当前参数退票
     */
    private Boolean refundable;

    /**
     * 预计退款总金额
     */
    private Integer refundAmount;

    /**
     * 本次选择的可退车票
     */
    private List<RefundTicketItemRespDTO> items;

    /**
     * 不可退时的安全原因
     */
    private String reason;
}
