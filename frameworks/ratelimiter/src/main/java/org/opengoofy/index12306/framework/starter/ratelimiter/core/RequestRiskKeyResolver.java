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

package org.opengoofy.index12306.framework.starter.ratelimiter.core;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.ratelimiter.enums.RateLimitDimensionEnum;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 限流/风控通用维度 Key 解析工具，按用户或 IP 维度生成统一的风控标识，供限流与验证码风控复用
 */
public final class RequestRiskKeyResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    /**
     * 嫌疑名单标记 Key 前缀，${unique-name:} 用于多环境共享同一个 Redis 实例时做命名隔离
     */
    private static final String SUSPECT_KEY_PREFIX = "index12306-risk${unique-name:}:suspect:";

    private RequestRiskKeyResolver() {
    }

    /**
     * 解析当前请求的风控维度标识，格式为 user:{userId} 或 ip:{clientIp}
     *
     * @param trustForwardedHeader 是否信任 X-Forwarded-For 请求头，仅在前置有可信反向代理时应为 true
     */
    public static String resolveDimensionValue(RateLimitDimensionEnum dimension, HttpServletRequest request, boolean trustForwardedHeader) {
        String userId = UserContext.getUserId();
        return switch (dimension) {
            case USER -> {
                if (StrUtil.isBlank(userId)) {
                    throw new ClientException("用户ID获取失败，请登录");
                }
                yield "user:" + userId;
            }
            case USER_THEN_IP -> StrUtil.isBlank(userId) ? "ip:" + getClientIp(request, trustForwardedHeader) : "user:" + userId;
            default -> "ip:" + getClientIp(request, trustForwardedHeader);
        };
    }

    /**
     * @param trustForwardedHeader 是否信任 X-Forwarded-For 请求头，仅在前置有可信反向代理时应为 true，
     *                              否则客户端可任意伪造该头，直接使用 TCP 连接的远端地址
     */
    public static String getClientIp(HttpServletRequest request, boolean trustForwardedHeader) {
        if (trustForwardedHeader) {
            String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
            if (StrUtil.isNotBlank(forwardedFor)) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * 构建嫌疑名单标记 Key，限流与验证码风控必须使用同一构建方式才能命中同一个标记
     */
    public static String buildSuspectKey(String dimensionValue, ConfigurableEnvironment environment) {
        return environment.resolvePlaceholders(SUSPECT_KEY_PREFIX + dimensionValue);
    }
}
