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

import org.opengoofy.index12306.biz.ticketservice.service.monitor.TicketOrderCreationMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;

/**
 * 异步订单创建执行器配置。
 */
@Configuration
public class TicketOrderCreationExecutorConfiguration {

    /**
     * 创建有界建单执行器，隔离 HTTP 线程与后台订单远程调用。
     *
     * @param corePoolSize 常驻建单线程数
     * @param maxPoolSize 建单峰值线程数
     * @param queueCapacity 待执行任务队列容量
     * @return 专用异步建单执行器
     */
    @Bean("ticketOrderCreationExecutor")
    public ThreadPoolTaskExecutor ticketOrderCreationExecutor(
            @Value("${index12306.ticket.async-order.core-pool-size:8}") int corePoolSize,
            @Value("${index12306.ticket.async-order.max-pool-size:16}") int maxPoolSize,
            @Value("${index12306.ticket.async-order.queue-capacity:16}") int queueCapacity,
            TicketOrderCreationMetrics ticketOrderCreationMetrics) {
        // 线程和队列都设置上限，避免订单服务变慢时无界堆积内存。
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("ticket-order-create-");
        // 拒绝必须显式回传给 Outbox 调度器，不能让调度线程代跑远程建单请求。
        executor.setRejectedExecutionHandler((task, threadPool) -> {
            ticketOrderCreationMetrics.recordExecutorRejected();
            throw new RejectedExecutionException("ticket order creation executor is saturated");
        });
        // 应用正常下线时给已领取任务短暂完成时间，超时任务由 Outbox 租约恢复。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        // 执行器 Gauge 只读取内存运行态，供 Actuator 与 Prometheus 直接抓取。
        ticketOrderCreationMetrics.registerExecutor(executor);
        return executor;
    }
}
