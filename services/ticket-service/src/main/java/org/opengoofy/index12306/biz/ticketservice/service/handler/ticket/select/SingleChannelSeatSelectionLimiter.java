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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * 限制单个 ticket-service 实例同时执行的区间锁选座请求数，保护请求线程与 Redis 锁服务。
 */
@Component
public class SingleChannelSeatSelectionLimiter {

    private final Semaphore semaphore;

    /**
     * 按配置创建非公平本机信号量，超出容量的请求立即返回而不排队占用 Tomcat 线程。
     *
     * @param maxConcurrency 单实例单通道最大并发数
     */
    public SingleChannelSeatSelectionLimiter(
            @Value("${ticket.seat.single-channel.max-concurrency:32}") int maxConcurrency) {
        // 至少保留一个许可，错误配置不能让所有低余票请求永久不可用。
        semaphore = new Semaphore(Math.max(1, maxConcurrency));
    }

    /**
     * 尝试获取一次单通道选座许可，不等待已有请求完成。
     *
     * @return 获取成功返回 true；达到并发上限返回 false
     */
    public boolean tryAcquire() {
        // 快速失败把排队压力留在网关或上游，避免单通道热点耗尽业务线程。
        return semaphore.tryAcquire();
    }

    /**
     * 归还当前请求完成后的单通道选座许可。
     */
    public void release() {
        // 调用方只在成功获取许可后调用，信号量恢复使下一请求可立即开始尝试。
        semaphore.release();
    }
}
