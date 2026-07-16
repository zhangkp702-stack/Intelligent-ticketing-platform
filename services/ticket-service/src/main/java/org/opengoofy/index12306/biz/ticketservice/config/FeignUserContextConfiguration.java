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

package org.opengoofy.index12306.biz.ticketservice.config;

import feign.RequestInterceptor;
import org.opengoofy.index12306.framework.starter.bases.constant.UserConstant;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 票务服务调用下游服务时的用户上下文透传配置。
 */
@Configuration
public class FeignUserContextConfiguration {

    /**
     * 将当前请求中已经验证的用户标识传递给订单等下游服务。
     *
     * @return 用户请求头 Feign 拦截器
     */
    @Bean
    public RequestInterceptor userContextRequestInterceptor() {
        return requestTemplate -> {
            // 只透传当前线程中已经建立的用户上下文，不自行构造或接受业务参数中的用户标识。
            if (StringUtils.hasText(UserContext.getUserId())) {
                requestTemplate.header(UserConstant.USER_ID_KEY, UserContext.getUserId());
            }
            if (StringUtils.hasText(UserContext.getUsername())) {
                requestTemplate.header(UserConstant.USER_NAME_KEY, UserContext.getUsername());
            }
            if (StringUtils.hasText(UserContext.getRealName())) {
                requestTemplate.header(UserConstant.REAL_NAME_KEY, UserContext.getRealName());
            }
        };
    }
}
