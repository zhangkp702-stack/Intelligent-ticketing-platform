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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 订单可靠消息后台投递线程池配置。
 */
@Configuration
public class OrderMessageDispatchConfiguration {

    /**
     * 创建延迟关单消息投递线程池，隔离 RocketMQ 网络等待与订单请求线程。
     *
     * @return 有界的延迟关单消息执行器
    */
    @Bean("delayCloseMessageExecutor")
    public ThreadPoolTaskExecutor delayCloseMessageExecutor() {
        // 有界队列保护进程内存；队列满时拒绝即时任务，持久化 Outbox 会由恢复扫描重新投递。
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(5000);
        executor.setThreadNamePrefix("delay-close-message-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
