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
 * 返回当前用户业务操作的持久化状态和脱敏结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessOperationStatusRespDTO {

    /**
     * 调用方生成的稳定操作标识。
     */
    private String operationId;

    /**
     * 购票、取消或退票操作类型。
     */
    private String operationType;

    /**
     * PROCESSING、SUCCEEDED 或 FAILED。
     */
    private String status;

    /**
     * 成功时可返回 Agent 的白名单结果 JSON。
     */
    private String safeResultJson;

    /**
     * 明确失败时的限长原因摘要。
     */
    private String failureMessage;
}
