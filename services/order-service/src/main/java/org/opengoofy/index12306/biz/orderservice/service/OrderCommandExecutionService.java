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

package org.opengoofy.index12306.biz.orderservice.service;

/**
 * 订单创建 Command 的租约执行与分片恢复入口。
 */
public interface OrderCommandExecutionService {

    /**
     * 异步唤醒一个已经持久化的订单创建任务。
     *
     * @param userId 用户分片键
     * @param reservationId 稳定购票受理标识
     */
    void dispatchAsync(String userId, String reservationId);
}
