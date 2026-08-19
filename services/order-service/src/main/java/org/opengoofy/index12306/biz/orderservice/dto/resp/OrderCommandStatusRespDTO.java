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

package org.opengoofy.index12306.biz.orderservice.dto.resp;

import lombok.Builder;
import lombok.Data;

/**
 * 订单命令权威状态，只暴露恢复执行所需的安全字段。
 */
@Data
@Builder
public class OrderCommandStatusRespDTO {

    /**
     * 稳定命令标识。
     */
    private String commandId;

    /**
     * 业务动作标识。
     */
    private String actionId;

    /**
     * 命令状态：NOT_FOUND、PROCESSING、SUCCEEDED 或 FAILED。
     */
    private String status;

    /**
     * 成功创建的订单号。
     */
    private String orderSn;
}
