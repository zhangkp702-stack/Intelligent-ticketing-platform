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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationLeaseService;
import org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 只为当前 JVM 内仍在执行的操作续租，避免服务存活却永久续租已经丢失的请求。
 */
@Service
public class BusinessOperationLeaseServiceImpl implements BusinessOperationLeaseService {

    private final BusinessOperationTransactionService transactionService;
    private final long leaseDurationMillis;
    private final String workerId = "ticket-operation-" + UUID.randomUUID();
    private final ConcurrentMap<String, OperationLease> activeLeases = new ConcurrentHashMap<>();

    /**
     * 创建同步业务操作租约服务。
     *
     * @param transactionService 独立事务状态服务
     * @param leaseDurationMillis 租约时长毫秒数
     */
    public BusinessOperationLeaseServiceImpl(
            BusinessOperationTransactionService transactionService,
            @Value("${index12306.ticket.operation.lease-duration-millis:120000}")
            long leaseDurationMillis) {
        this.transactionService = transactionService;
        this.leaseDurationMillis = Math.max(30000L, leaseDurationMillis);
    }

    /**
     * 为新操作生成初始 epoch 和租约时间。
     *
     * @param operationId 业务操作标识
     * @return 初始租约
     */
    @Override
    public OperationLease create(String operationId) {
        Date now = new Date();
        // operationId 首次插入时 epoch 固定从 1 开始，后续接管必须递增后才能提交。
        return new OperationLease(
                operationId, workerId, 1L, now,
                new Date(now.getTime() + leaseDurationMillis));
    }

    /**
     * 登记当前线程已经开始真实写调用。
     *
     * @param lease 当前执行租约
     */
    @Override
    public void activate(OperationLease lease) {
        activeLeases.put(lease.operationId(), lease);
    }

    /**
     * 写调用结束后删除活动租约，定时器不再续租。
     *
     * @param lease 当前执行租约
     */
    @Override
    public void deactivate(OperationLease lease) {
        activeLeases.remove(lease.operationId(), lease);
    }

    /**
     * 为本实例仍在执行的业务操作周期写入数据库心跳。
     */
    @Scheduled(fixedDelayString = "${index12306.ticket.operation.heartbeat-interval-millis:30000}")
    public void heartbeatActiveOperations() {
        Date now = new Date();
        Date deadline = new Date(now.getTime() + leaseDurationMillis);
        for (OperationLease lease : activeLeases.values()) {
            // 任一 CAS 续租失败都说明当前请求已经失去执行权，立即停止为它续租。
            boolean renewed = transactionService.heartbeat(
                    lease.operationId(), lease.owner(), lease.epoch(), now, deadline);
            if (!renewed) {
                activeLeases.remove(lease.operationId(), lease);
            }
        }
    }
}
