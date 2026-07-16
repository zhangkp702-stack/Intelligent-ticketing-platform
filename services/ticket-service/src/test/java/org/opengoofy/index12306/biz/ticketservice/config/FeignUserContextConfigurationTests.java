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
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.framework.starter.bases.constant.UserConstant;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证票务服务调用下游服务时会透传当前用户身份。
 */
class FeignUserContextConfigurationTests {

    /**
     * 清理测试线程中的用户上下文。
     */
    @AfterEach
    void clearUserContext() {
        // 用户上下文基于线程本地变量，测试结束后必须显式移除。
        UserContext.removeUser();
    }

    /**
     * 验证 Feign 请求包含订单服务归属校验需要的用户请求头。
     */
    @Test
    void interceptorPropagatesAuthenticatedUserHeaders() {
        UserContext.setUser(UserInfoDTO.builder()
                .userId("1001")
                .username("alice")
                .realName("Alice")
                .build());
        RequestInterceptor interceptor = new FeignUserContextConfiguration().userContextRequestInterceptor();
        RequestTemplate template = new RequestTemplate();

        // 下游服务只从请求头建立用户上下文，不能从订单参数信任用户标识。
        interceptor.apply(template);
        assertThat(template.headers().get(UserConstant.USER_ID_KEY)).containsExactly("1001");
        assertThat(template.headers().get(UserConstant.USER_NAME_KEY)).containsExactly("alice");
        assertThat(template.headers().get(UserConstant.REAL_NAME_KEY)).containsExactly("Alice");
        assertThat(template.headers()).doesNotContainKey(UserConstant.USER_TOKEN_KEY);
    }
}
