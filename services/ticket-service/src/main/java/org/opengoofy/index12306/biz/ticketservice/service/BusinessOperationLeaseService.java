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

import java.util.Date;

/**
 * 管理 ticket-service 同步业务写操作的实例租约和周期心跳。
 */
public interface BusinessOperationLeaseService {

    /**
     * 为新认领的业务操作创建实例租约。
     *
     * @param operationId 业务操作标识
     * @return 初始租约
     */
    OperationLease create(String operationId);

    /**
     * 在真实写调用存活期间登记周期心跳。
     *
     * @param lease 当前执行租约
     */
    void activate(OperationLease lease);

    /**
     * 在写调用结束后停止续租。
     *
     * @param lease 当前执行租约
     */
    void deactivate(OperationLease lease);

    /**
     * 同步写操作的数据库执行权。
     *
     * @param operationId 业务操作标识
     * @param owner 执行实例标识
     * @param epoch 隔离版本
     * @param heartbeatAt 初始心跳时间
     * @param leaseUntil 初始租约截止时间
     */
    record OperationLease(
            String operationId,
            String owner,
            long epoch,
            Date heartbeatAt,
            Date leaseUntil) {
    }
}
