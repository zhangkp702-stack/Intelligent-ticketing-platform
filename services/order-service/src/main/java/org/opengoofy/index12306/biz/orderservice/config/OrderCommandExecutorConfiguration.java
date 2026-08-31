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

package org.opengoofy.index12306.biz.orderservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 订单创建 Command 的有界执行器配置。
 */
@Configuration
public class OrderCommandExecutorConfiguration {

    /**
     * 创建与 MQ 消费线程、定时恢复线程隔离的订单任务执行器。
     *
     * @param corePoolSize 常驻线程数
     * @param maxPoolSize 最大线程数
     * @param queueCapacity 有界等待队列容量
     * @return 已初始化的订单任务执行器
     */
    @Bean("orderCommandExecutor")
    public ThreadPoolTaskExecutor orderCommandExecutor(
            @Value("${index12306.order.command.executor.core-pool-size:16}") int corePoolSize,
            @Value("${index12306.order.command.executor.max-pool-size:64}") int maxPoolSize,
            @Value("${index12306.order.command.executor.queue-capacity:2048}") int queueCapacity) {
        // 有界队列提供进程内背压；队列满时拒绝即时调度，由数据库恢复扫描稍后重新提交。
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("order-command-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
