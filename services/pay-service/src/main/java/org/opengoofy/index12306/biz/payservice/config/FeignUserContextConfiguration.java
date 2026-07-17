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

package org.opengoofy.index12306.biz.payservice.config;

import feign.RequestInterceptor;
import org.opengoofy.index12306.framework.starter.bases.constant.UserConstant;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 支付服务调用订单和用户服务时的用户上下文透传配置。
 */
@Configuration
public class FeignUserContextConfiguration {

    /**
     * 将网关验证后的当前用户身份自动写入 Feign 请求头。
     *
     * @return 用户上下文请求拦截器
     */
    @Bean
    public RequestInterceptor userContextRequestInterceptor() {
        return requestTemplate -> {
            // 下游服务只信任请求上下文，不从支付请求体接收用户标识。
            if (StringUtils.hasText(UserContext.getUserId())) {
                requestTemplate.header(UserConstant.USER_ID_KEY, UserContext.getUserId());
            }
            if (StringUtils.hasText(UserContext.getUsername())) {
                requestTemplate.header(
                        UserConstant.USER_NAME_KEY,
                        URLEncoder.encode(UserContext.getUsername(), StandardCharsets.UTF_8));
            }
            if (StringUtils.hasText(UserContext.getRealName())) {
                requestTemplate.header(
                        UserConstant.REAL_NAME_KEY,
                        URLEncoder.encode(UserContext.getRealName(), StandardCharsets.UTF_8));
            }
        };
    }
}
