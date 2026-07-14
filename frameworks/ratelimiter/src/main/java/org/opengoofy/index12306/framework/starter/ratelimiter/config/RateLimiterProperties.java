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

package org.opengoofy.index12306.framework.starter.ratelimiter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流/风控通用配置
 */
@Data
@ConfigurationProperties(prefix = "index12306.ratelimiter")
public class RateLimiterProperties {

    /**
     * 是否信任请求头 X-Forwarded-For 作为客户端 IP。
     * 只有当应用前面确实存在会清洗并重写该请求头的可信反向代理（Nginx/SLB/CDN）时才应开启，
     * 否则客户端可以任意伪造该请求头，绕过 IP 维度限流或冒名顶替他人 IP
     */
    private boolean trustForwardedHeader = false;
}
