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

package org.opengoofy.index12306.biz.orderservice.dao.algorithm;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 recovery_shard 能将后台恢复查询定向到一个数据库和一张物理表。
 */
class OrderCommandRecoveryShardingTests {

    private OrderCommonDataBaseComplexAlgorithm databaseAlgorithm;
    private OrderCommonTableComplexAlgorithm tableAlgorithm;

    /**
     * 使用当前生产配置的两个库、三十二张逻辑分片初始化算法。
     */
    @BeforeEach
    void setUp() {
        Properties databaseProperties = new Properties();
        databaseProperties.setProperty("sharding-count", "32");
        databaseProperties.setProperty("table-sharding-count", "16");
        databaseAlgorithm = new OrderCommonDataBaseComplexAlgorithm();
        databaseAlgorithm.init(databaseProperties);

        Properties tableProperties = new Properties();
        tableProperties.setProperty("sharding-count", "32");
        tableAlgorithm = new OrderCommonTableComplexAlgorithm();
        tableAlgorithm.init(tableProperties);
    }

    /**
     * 验证恢复分片 17 只访问第二个数据库中的第 17 张 Command 表。
     */
    @Test
    void recoveryShardRoutesSinglePhysicalNode() {
        ComplexKeysShardingValue<Integer> shardingValue = new ComplexKeysShardingValue<>(
                "t_order_command", Map.of("recovery_shard", List.of(17)), Map.of());

        Collection<String> databases = databaseAlgorithm.doSharding(
                List.of("ds_0", "ds_1"), shardingValue);
        Collection<String> tables = tableAlgorithm.doSharding(
                List.of("t_order_command_0", "t_order_command_17"), shardingValue);

        assertThat(databases).containsExactly("ds_1");
        assertThat(tables).containsExactly("t_order_command_17");
    }

    /**
     * 验证持久化阶段计算出的恢复分片与正常 user_id 路由命中同一物理节点。
     */
    @Test
    void recoveryShardMatchesUserRoute() {
        ComplexKeysShardingValue<String> userRoute = new ComplexKeysShardingValue<>(
                "t_order_command", Map.of("user_id", List.of("1001")), Map.of());
        ComplexKeysShardingValue<Integer> recoveryRoute = new ComplexKeysShardingValue<>(
                "t_order_command", Map.of("recovery_shard", List.of(0)), Map.of());

        assertThat(databaseAlgorithm.doSharding(List.of("ds_0", "ds_1"), userRoute))
                .containsExactlyElementsOf(databaseAlgorithm.doSharding(
                        List.of("ds_0", "ds_1"), recoveryRoute));
        assertThat(tableAlgorithm.doSharding(
                List.of("t_order_command_0", "t_order_command_1"), userRoute))
                .containsExactlyElementsOf(tableAlgorithm.doSharding(
                        List.of("t_order_command_0", "t_order_command_1"), recoveryRoute));
    }
}
