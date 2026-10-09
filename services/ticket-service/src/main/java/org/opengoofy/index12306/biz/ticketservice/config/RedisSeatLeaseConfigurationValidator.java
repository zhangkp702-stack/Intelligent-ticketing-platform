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

package org.opengoofy.index12306.biz.ticketservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 校验 Redis 临时占座租约与 reservation 恢复链路的时间配置关系。
 *
 * <p>租约到期会触发 Redis owner 回收；若其早于 MySQL 恢复宽限期，慢请求可能被过早中止。
 * 因此启用租约扫描时必须在应用启动阶段拒绝危险配置。</p>
 */
@Slf4j
@Component
public class RedisSeatLeaseConfigurationValidator implements InitializingBean {

    @Value("${index12306.ticket.redis-seat-lease.enabled:true}")
    private boolean redisSeatLeaseRecoveryEnabled;

    @Value("${index12306.ticket.redis-seat-lease.lease-millis:120000}")
    private long redisSeatLeaseMillis;

    @Value("${index12306.ticket.redis-seat-lease.recovery-interval-millis:5000}")
    private long redisSeatLeaseRecoveryIntervalMillis;

    @Value("${index12306.ticket.redis-seat-lease.batch-size:100}")
    private int redisSeatLeaseRecoveryBatchSize;

    @Value("${index12306.ticket.reservation-recovery.timeout-millis:60000}")
    private long reservationRecoveryTimeoutMillis;

    /**
     * 在 Bean 初始化完成后验证租约扫描的时间窗口和批处理参数。
     */
    @Override
    public void afterPropertiesSet() {
        if (!redisSeatLeaseRecoveryEnabled) {
            // 功能显式关闭时不限制预留参数，便于灰度回滚时保留原配置值。
            return;
        }
        if (redisSeatLeaseRecoveryIntervalMillis <= 0 || redisSeatLeaseRecoveryBatchSize <= 0) {
            throw new IllegalStateException("Redis 座位租约扫描周期和批次必须为正数");
        }
        if (reservationRecoveryTimeoutMillis <= 0 || redisSeatLeaseMillis <= 0) {
            throw new IllegalStateException("Redis 座位租约和 reservation 恢复宽限期必须为正数");
        }
        // 至少预留两轮扫描抖动；同时让常规 MySQL 恢复任务先有机会处理 INIT。
        long minimumLeaseMillis = Math.max(reservationRecoveryTimeoutMillis,
                redisSeatLeaseRecoveryIntervalMillis * 2L);
        if (redisSeatLeaseMillis < minimumLeaseMillis) {
            throw new IllegalStateException(String.format(
                    "Redis 座位租约过短：lease-millis=%d，至少应为 %d", redisSeatLeaseMillis, minimumLeaseMillis));
        }
        log.info("ticket_redis_seat_lease_configuration_validated leaseMillis={}, recoveryIntervalMillis={}, batchSize={}",
                redisSeatLeaseMillis, redisSeatLeaseRecoveryIntervalMillis, redisSeatLeaseRecoveryBatchSize);
    }
}
