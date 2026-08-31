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

package org.opengoofy.index12306.biz.canalrelay.config;

import com.alibaba.otter.canal.client.CanalConnectors;
import org.opengoofy.index12306.biz.canalrelay.relay.CanalConnectorFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 装配 Canal 客户端和受限 MQ 发送线程池。
 */
@Configuration
@EnableConfigurationProperties(CanalRelayProperties.class)
public class CanalRelayConfiguration {

    /**
     * 创建可替换的 Canal 连接器工厂，测试可注入模拟连接器验证 ACK 与回滚顺序。
     *
     * @return 使用当前配置建立单 destination 连接的工厂
     */
    @Bean
    public CanalConnectorFactory canalConnectorFactory() {
        return properties -> CanalConnectors.newSingleConnector(
                parseAddress(properties.getAddress()),
                properties.getDestination(),
                properties.getUsername(),
                properties.getPassword());
    }

    /**
     * 将配置中的 host:port 转换为 Canal 客户端所需的 SocketAddress。
     *
     * @param address 原始 Canal 地址
     * @return 已校验端口范围的 SocketAddress
     */
    private InetSocketAddress parseAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Canal 地址不能为空");
        }
        int separatorIndex = address.lastIndexOf(':');
        if (separatorIndex <= 0 || separatorIndex == address.length() - 1) {
            throw new IllegalArgumentException("Canal 地址必须为 host:port");
        }
        String host = address.substring(0, separatorIndex);
        try {
            int port = Integer.parseInt(address.substring(separatorIndex + 1));
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Canal 端口必须在 1 到 65535 之间");
            }
            return new InetSocketAddress(host, port);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Canal 端口不是有效整数", exception);
        }
    }

    /**
     * 创建有界发送执行器，队列饱和会使当前 Canal 批次回滚而不是无限占用 JVM 内存。
     *
     * @param properties Relay 并发和队列配置
     * @return 由 Spring 关闭的 RocketMQ 发送执行器
     */
    @Bean(name = "canalRelayPublisherExecutor", destroyMethod = "shutdown")
    public ExecutorService canalRelayPublisherExecutor(CanalRelayProperties properties) {
        int concurrency = Math.max(1, properties.getSendConcurrency());
        int queueCapacity = Math.max(1, properties.getSendQueueCapacity());
        AtomicInteger sequence = new AtomicInteger();
        // 使用独立线程池隔离慢 Broker，Relay 主线程只负责 Canal 连接和批次 ACK。
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "canal-relay-publisher-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        return new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
