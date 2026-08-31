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

import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.ticketorder.contract.TicketOrderCreateCommandMessage;

/**
 * 订单创建 Command 的可靠接收持久化服务。
 */
public interface OrderCommandPersistenceService {

    /**
     * 在本地事务中持久化可独立恢复的订单创建任务。
     *
     * <p>相同命令和相同请求幂等返回原记录；相同命令绑定不同请求时拒绝覆盖。</p>
     *
     * @param message 票务服务发布的完整订单创建消息
     * @return 新建或幂等复用的 Command 记录
     */
    OrderCommandDO persistReceivedCommand(TicketOrderCreateCommandMessage message);
}
