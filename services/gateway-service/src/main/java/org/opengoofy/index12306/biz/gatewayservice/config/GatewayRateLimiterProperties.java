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

package org.opengoofy.index12306.biz.gatewayservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关层 IP 限流与自动拉黑配置
 */
@Data
@ConfigurationProperties(prefix = "index12306.gateway.rate-limiter")
public class GatewayRateLimiterProperties {

    /**
     * 是否启用网关层限流
     */
    private boolean enabled = true;

    /**
     * 是否信任请求头 X-Forwarded-For 作为客户端 IP。
     * 只有当网关前面确实存在会清洗并重写该请求头的可信反向代理（Nginx/SLB/CDN）时才应开启，
     * 否则客户端可以任意伪造该请求头，绕过限流或冒名顶替他人 IP 被拉黑
     */
    private boolean trustForwardedHeader = false;

    /**
     * 同一 IP 每秒允许通过的请求数
     */
    private int permitsPerSecond = 50;

    /**
     * 触发限流的累计次数达到该阈值后，自动拉黑该 IP
     */
    private int violationThreshold = 20;

    /**
     * 违规计数窗口，单位秒
     */
    private long violationWindowSeconds = 600L;

    /**
     * 自动拉黑后的封禁时长，单位秒
     */
    private long blacklistTtlSeconds = 3600L;
}
