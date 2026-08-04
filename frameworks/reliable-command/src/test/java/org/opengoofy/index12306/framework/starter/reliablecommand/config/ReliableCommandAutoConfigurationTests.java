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

package org.opengoofy.index12306.framework.starter.reliablecommand.config;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.framework.starter.reliablecommand.aop.ReliableCommandExecutionAspect;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandFingerprint;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandService;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.store.ReliableCommandStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Starter 在业务服务已有数据源时可以完成最小自动装配。
 */
class ReliableCommandAutoConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    ReliableCommandAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:reliable_autoconfig;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa");

    /**
     * 验证摘要器、JDBC 存储和远程命令服务复用应用数据源及事务管理器创建。
     */
    @Test
    void shouldCreateReliableCommandBeans() {
        contextRunner.run(context -> {
            // Starter 不创建第二个数据源，只在现有 JDBC 基础设施上注册通用能力。
            assertThat(context).hasSingleBean(ReliableCommandFingerprint.class);
            assertThat(context).hasSingleBean(ReliableCommandStore.class);
            assertThat(context).hasSingleBean(ReliableCommandService.class);
            assertThat(context).hasSingleBean(ReliableEventStore.class);
            assertThat(context).hasSingleBean(ReliableCommandExecutionAspect.class);
        });
    }
}
