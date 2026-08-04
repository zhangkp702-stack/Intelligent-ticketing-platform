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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.JacksonReliableCommandFingerprint;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandFingerprint;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandService;
import org.opengoofy.index12306.framework.starter.reliablecommand.aop.ReliableCommandExecutionAspect;
import org.opengoofy.index12306.framework.starter.reliablecommand.event.ReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.jdbc.JdbcReliableEventStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.jdbc.JdbcReliableCommandStore;
import org.opengoofy.index12306.framework.starter.reliablecommand.store.ReliableCommandStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.UUID;

/**
 * 基于当前服务数据源装配可靠命令摘要器和 JDBC 存储。
 */
@AutoConfiguration(after = {
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
@ConditionalOnClass({JdbcTemplate.class, ReliableCommandStore.class})
public class ReliableCommandAutoConfiguration {

    /**
     * 创建稳定 JSON 请求摘要器，允许业务应用提供自定义实现。
     *
     * @param objectMapperProvider 应用 Jackson 配置提供器
     * @return 可靠命令请求摘要器
     */
    @Bean
    @ConditionalOnMissingBean
    public ReliableCommandFingerprint reliableCommandFingerprint(
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        // 优先复用应用已注册模块的 ObjectMapper；纯 JDBC 应用没有该 Bean 时使用默认配置。
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new JacksonReliableCommandFingerprint(objectMapper);
    }

    /**
     * 使用服务本地事务管理器创建可靠命令存储。
     *
     * @param jdbcTemplate 当前服务数据源访问器
     * @param transactionManager 当前服务事务管理器
     * @return JDBC 可靠命令存储
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
    public ReliableCommandStore reliableCommandStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        // 存储必须与业务服务共享数据源和事务管理器，不能改为远程中央幂等服务。
        return new JdbcReliableCommandStore(jdbcTemplate, transactionManager);
    }

    /**
     * 使用服务本地事务管理器创建可靠 Outbox 和 Inbox 存储。
     *
     * @param jdbcTemplate 当前服务数据源访问器
     * @param transactionManager 当前服务事务管理器
     * @return JDBC 可靠事件存储
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
    public ReliableEventStore reliableEventStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        // 事件和业务状态必须共享本地事务，避免业务已提交但 Outbox 丢失。
        return new JdbcReliableEventStore(jdbcTemplate, transactionManager);
    }

    /**
     * 创建远程副作用命令的短事务和活动租约协调服务。
     *
     * @param store 可靠命令持久化接口
     * @param transactionManager 当前服务事务管理器
     * @param executionLeaseMillis 真实业务执行租约毫秒数
     * @return 可供业务模块直接调用的可靠命令服务
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ReliableCommandStore.class, PlatformTransactionManager.class})
    public ReliableCommandService reliableCommandService(
            ReliableCommandStore store,
            PlatformTransactionManager transactionManager,
            @Value("${index12306.reliable-command.execution-lease-millis:120000}")
            long executionLeaseMillis) {
        // 每个服务实例使用独立 workerId，数据库围栏负责拒绝租约失效后的迟到写入。
        long normalizedLeaseMillis = Math.max(30000L, executionLeaseMillis);
        return new ReliableCommandService(
                store,
                transactionManager,
                Duration.ofMillis(normalizedLeaseMillis),
                "reliable-command-" + UUID.randomUUID());
    }

    /**
     * 创建远程命令入口注解的 AOP 适配器。
     *
     * @param reliableCommandService 远程副作用可靠命令服务
     * @param reliableCommandFingerprint 请求摘要器
     * @param objectMapperProvider 应用 JSON 序列化器提供器
     * @return 可靠命令执行入口切面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ReliableCommandService.class, ReliableCommandFingerprint.class})
    public ReliableCommandExecutionAspect reliableCommandExecutionAspect(
            ReliableCommandService reliableCommandService,
            ReliableCommandFingerprint reliableCommandFingerprint,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        // 重放结果应复用应用的日期和领域模块配置；没有全局配置时使用默认 Jackson。
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new ReliableCommandExecutionAspect(
                reliableCommandService, reliableCommandFingerprint, objectMapper);
    }
}
