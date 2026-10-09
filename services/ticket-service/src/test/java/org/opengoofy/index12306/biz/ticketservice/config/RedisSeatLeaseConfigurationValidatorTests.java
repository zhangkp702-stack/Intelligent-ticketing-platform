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

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Redis 座位租约的启动期配置护栏。
 */
class RedisSeatLeaseConfigurationValidatorTests {

    /**
     * 默认的 120 秒租约应大于 60 秒恢复宽限期，可安全启动。
     */
    @Test
    void acceptsLeaseLongerThanRecoveryWindow() {
        RedisSeatLeaseConfigurationValidator validator = validator(true, 120_000L, 5_000L, 100, 60_000L);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    /**
     * 租约短于 MySQL 恢复宽限期时必须拒绝启动，避免慢请求被过早回收。
     */
    @Test
    void rejectsLeaseShorterThanReservationRecoveryTimeout() {
        RedisSeatLeaseConfigurationValidator validator = validator(true, 30_000L, 5_000L, 100, 60_000L);

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis 座位租约过短");
    }

    /**
     * 灰度关闭租约恢复时允许保留尚未调整的参数，避免回滚过程被配置校验阻断。
     */
    @Test
    void skipsLeaseValidationWhenRecoveryIsDisabled() {
        RedisSeatLeaseConfigurationValidator validator = validator(false, 1L, 0L, 0, 0L);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    /**
     * 构造填充完整配置的启动期校验器。
     *
     * @param enabled 是否启用租约扫描
     * @param leaseMillis Redis 租约时长
     * @param scanIntervalMillis 租约扫描周期
     * @param batchSize 单次扫描批次
     * @param reservationTimeoutMillis reservation 恢复宽限期
     * @return 可直接执行初始化校验的对象
     */
    private RedisSeatLeaseConfigurationValidator validator(boolean enabled, long leaseMillis, long scanIntervalMillis,
                                                           int batchSize, long reservationTimeoutMillis) {
        RedisSeatLeaseConfigurationValidator validator = new RedisSeatLeaseConfigurationValidator();
        ReflectionTestUtils.setField(validator, "redisSeatLeaseRecoveryEnabled", enabled);
        ReflectionTestUtils.setField(validator, "redisSeatLeaseMillis", leaseMillis);
        ReflectionTestUtils.setField(validator, "redisSeatLeaseRecoveryIntervalMillis", scanIntervalMillis);
        ReflectionTestUtils.setField(validator, "redisSeatLeaseRecoveryBatchSize", batchSize);
        ReflectionTestUtils.setField(validator, "reservationRecoveryTimeoutMillis", reservationTimeoutMillis);
        return validator;
    }
}
