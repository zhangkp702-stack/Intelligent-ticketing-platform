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

import org.opengoofy.index12306.biz.orderservice.service.monitor.OrderCreateResultOutboxMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;

/**
 * 配置订单创建结果 Outbox 的有界并发投递执行器。
 */
@Configuration
public class OrderCreateResultOutboxExecutorConfiguration {

    /**
     * 创建隔离 Broker 发送的有界执行器，并在饱和时让事件立即回退 Outbox。
     *
     * @param corePoolSize 常驻投递线程数
     * @param maxPoolSize 峰值投递线程数
     * @param queueCapacity 待发送结果消息的队列容量
     * @param metrics 结果 Outbox 指标组件
     * @return 已初始化的专用结果投递执行器
     */
    @Bean("orderCreateResultOutboxExecutor")
    public ThreadPoolTaskExecutor orderCreateResultOutboxExecutor(
            @Value("${index12306.order.create-result.core-pool-size:8}") int corePoolSize,
            @Value("${index12306.order.create-result.max-pool-size:16}") int maxPoolSize,
            @Value("${index12306.order.create-result.queue-capacity:16}") int queueCapacity,
            OrderCreateResultOutboxMetrics metrics) {
        // 固定上限隔离慢 Broker，不能让定时扫描线程直接执行网络发送。
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("order-create-result-");
        // 显式拒绝由发布器使用租约围栏回退，不使用 CallerRunsPolicy 隐藏容量告警。
        executor.setRejectedExecutionHandler((task, threadPool) -> {
            metrics.recordExecutorRejected();
            throw new RejectedExecutionException("order create result outbox executor is saturated");
        });
        // 正常关闭时给已领取任务短暂完成时间，超时任务仍由数据库租约恢复。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        metrics.registerExecutor(executor);
        return executor;
    }
}
