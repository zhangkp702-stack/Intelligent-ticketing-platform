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

package org.opengoofy.index12306.biz.ticketorder.contract;

/**
 * 订单异步消息的稳定协议常量。
 *
 * <p>常量只描述跨服务可见的协议，不负责切换当前 Feign 建单实现。</p>
 */
public final class TicketOrderMessageConstants {

    /** 订单创建命令主题。 */
    public static final String CREATE_ORDER_TOPIC = "INDEX12306_TICKET_ORDER";

    /** 订单创建命令标签。 */
    public static final String CREATE_ORDER_TAG = "CREATE_ORDER";

    /** 订单创建命令消费者组。 */
    public static final String CREATE_ORDER_CONSUMER_GROUP = "index12306_order-service_create-order_cg${unique-name:}";

    /** 订单创建结果标签。 */
    public static final String CREATE_ORDER_RESULT_TAG = "CREATE_ORDER_RESULT";

    /** 订单创建结果消费者组。 */
    public static final String CREATE_ORDER_RESULT_CONSUMER_GROUP = "index12306_ticket-service_create-order-result_cg${unique-name:}";

    /** 订单创建命令协议版本。 */
    public static final long CREATE_ORDER_VERSION = 2L;

    /** Outbox 事件命名空间，与当前锁座事务保持一致。 */
    public static final String CREATE_ORDER_NAMESPACE = "ticket-order-creation";

    /** Outbox 事件类型，与当前锁座事务保持一致。 */
    public static final String CREATE_ORDER_EVENT_TYPE = "CREATE_TICKET_ORDER";

    /** 订单创建结果 Outbox 命名空间。 */
    public static final String CREATE_ORDER_RESULT_NAMESPACE = "ticket-order-creation-result";

    /** 订单创建结果 Outbox 事件类型。 */
    public static final String CREATE_ORDER_RESULT_EVENT_TYPE = "TICKET_ORDER_CREATE_RESULT";

    /** 延迟关闭订单的目标 Topic 前缀。 */
    public static final String DELAY_CLOSE_ORDER_TOPIC = "index12306_order-service_delay-close-order_topic";

    /** 延迟关闭订单的目标 Tag 前缀。 */
    public static final String DELAY_CLOSE_ORDER_TAG = "index12306_order-service_delay-close-order_tag";

    /** 延迟关闭订单的 CDC Outbox 命名空间。 */
    public static final String DELAY_CLOSE_ORDER_NAMESPACE = "ticket-order-delay-close";

    /** 延迟关闭订单的 CDC Outbox 事件类型。 */
    public static final String DELAY_CLOSE_ORDER_EVENT_TYPE = "DELAY_CLOSE_TICKET_ORDER";

    /** 延迟关闭订单的跨服务协议版本。 */
    public static final long DELAY_CLOSE_ORDER_VERSION = 1L;

    /** RocketMQ 十分钟延迟投递等级。 */
    public static final int DELAY_CLOSE_ORDER_DELAY_LEVEL = 14;

    /** 成功结果状态。 */
    public static final String RESULT_SUCCEEDED = "SUCCEEDED";

    /** 失败结果状态。 */
    public static final String RESULT_FAILED = "FAILED";

    /**
     * 禁止实例化协议常量类。
     */
    private TicketOrderMessageConstants() {
    }
}
