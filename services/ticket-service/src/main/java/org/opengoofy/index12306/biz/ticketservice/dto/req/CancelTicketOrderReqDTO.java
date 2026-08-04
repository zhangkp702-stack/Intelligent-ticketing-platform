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

package org.opengoofy.index12306.biz.ticketservice.dto.req;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 取消车票订单请求入参
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CancelTicketOrderReqDTO {

    /**
     * Agent 已确认操作标识；普通取消请求可以不传。
     */
    private String operationId;

    /**
     * 下游取消订单步骤的稳定命令标识；由票务服务根据操作标识生成。
     */
    private String commandId;

    /**
     * 订单号
     */
    private String orderSn;

    /**
     * 保留操作标识和订单号构造方式，下游命令由票务服务执行前补齐。
     *
     * @param operationId Agent 操作标识
     * @param orderSn 订单号
     */
    public CancelTicketOrderReqDTO(String operationId, String orderSn) {
        // API 和测试仍可按既有两参数协议创建请求。
        this.operationId = operationId;
        this.orderSn = orderSn;
    }

    /**
     * 保留延迟关单等既有调用方使用的订单号构造方式。
     *
     * @param orderSn 订单号
     */
    public CancelTicketOrderReqDTO(String orderSn) {
        // 非 Agent 内部调用不生成操作标识，保持原有关闭订单语义。
        this.orderSn = orderSn;
    }
}
