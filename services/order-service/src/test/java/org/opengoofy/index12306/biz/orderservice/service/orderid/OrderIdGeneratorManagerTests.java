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

package org.opengoofy.index12306.biz.orderservice.service.orderid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证订单号中的用户分片基因保持固定六位。
 */
class OrderIdGeneratorManagerTests {

    /**
     * 验证用户 ID 末尾不足六位时补齐前导零，并与用户 ID 分片键保持相同哈希结果。
     */
    @Test
    void shouldKeepSixDigitUserShardingSuffix() {
        long userId = 1900000000000000001L;

        // 生成订单号使用的后缀必须保留用户 ID 原始末六位中的前导零。
        String actualSuffix = OrderIdGeneratorManager.formatUserIdShardingSuffix(userId);

        // 固定六位后，按订单号和用户 ID 执行字符串哈希会路由到同一张表。
        String expectedSuffix = String.valueOf(userId);
        expectedSuffix = expectedSuffix.substring(expectedSuffix.length() - 6);
        assertThat(actualSuffix).isEqualTo("000001");
        assertThat(Math.abs(actualSuffix.hashCode()) % 32)
                .isEqualTo(Math.abs(expectedSuffix.hashCode()) % 32);
    }
}
