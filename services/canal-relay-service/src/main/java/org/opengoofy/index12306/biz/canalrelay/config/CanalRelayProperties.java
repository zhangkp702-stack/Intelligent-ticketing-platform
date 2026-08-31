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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Canal Relay 的连接、订阅和背压配置。
 *
 * <p>所有字段仅描述 Relay 到 Canal 和 RocketMQ 的传输边界，不参与业务订单状态迁移。</p>
 */
@Data
@ConfigurationProperties(prefix = "index12306.canal-relay")
public class CanalRelayProperties {

    /** 是否启动 Relay 工作线程。 */
    private boolean enabled = true;

    /** Canal TCP 服务地址。 */
    private String address = "192.168.126.100:11111";

    /** Canal TCP 登录用户名；未开启鉴权时保持为空。 */
    private String username = "";

    /** Canal TCP 登录密码；未开启鉴权时保持为空。 */
    private String password = "";

    /** Canal destination 名称。 */
    private String destination = "example";

    /** 客户端订阅的数据库表正则。 */
    private String subscription = "12306_ticket\\.t_cdc_outbox_event,12306_order_0\\.t_cdc_outbox_event_.*,12306_order_1\\.t_cdc_outbox_event_.*";

    /** 允许路由的完整库表正则，按 schema.table 匹配。 */
    private List<String> sourceTablePatterns = List.of(
            "12306_ticket\\.t_cdc_outbox_event",
            "12306_order_0\\.t_cdc_outbox_event_[0-9]+",
            "12306_order_1\\.t_cdc_outbox_event_[0-9]+");

    /** 单次向 Canal 读取的最大 Binlog 条数。 */
    private int batchSize = 64;

    /** 空批次时的等待时间。 */
    private long idleBackoffMillis = 50L;

    /** 首次重连等待时间。 */
    private long reconnectInitialBackoffMillis = 1000L;

    /** 重连指数退避的最大等待时间。 */
    private long reconnectMaxBackoffMillis = 10000L;

    /** 同一 Binlog 批次中允许并行发送的最大消息数。 */
    private int sendConcurrency = 16;

    /** 有界发送队列容量，满时回滚 Canal 批次形成反压。 */
    private int sendQueueCapacity = 64;

    /** 单条消息在当前批次内允许的应用层发送次数，耗尽后才回滚整个批次。 */
    private int initialSendMaxAttempts = 3;

    /** 单条消息首次快速重试前的等待时间。 */
    private long initialSendRetryBackoffMillis = 200L;

    /** 单条消息快速重试等待时间的上限。 */
    private long initialSendRetryMaxBackoffMillis = 2000L;

    /** Relay 重试载荷的 AES 密钥，必须通过外部密钥配置注入，禁止写入代码仓库。 */
    private String payloadEncryptionKey = "";

    /** Relay 重试载荷当前使用的密钥版本。 */
    private int payloadKeyVersion = 1;
}
