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

package org.opengoofy.index12306.biz.ticketservice.service;

/**
 * 只通过下游权威查询恢复结果未知的票务业务操作。
 */
public interface BusinessOperationRecoveryService {

    /**
     * 扫描过期租约并处理当前到期的 UNKNOWN 操作。
     *
     * @return 本轮成功收敛为终态的操作数量
     */
    int recoverDueOperations();

    /**
     * 为当前用户指定操作触发一次只读对账。
     *
     * @param operationId 业务操作标识
     * @return 对账后最新状态
     */
    String reconcileNow(String operationId);
}
