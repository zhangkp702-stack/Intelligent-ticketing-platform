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

package org.opengoofy.index12306.framework.starter.captcha.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 腾讯云验证码（免费版）配置。AppID / AppSecretKey 需要在腾讯云控制台
 * "验证码"产品下申请，详细参数以腾讯云最新文档为准
 */
@Data
@ConfigurationProperties(prefix = "index12306.captcha.tencent")
public class TencentCaptchaProperties {

    /**
     * 是否启用验证码风控校验，关闭时直接放行（用于本地开发、未申请验证码账号时）
     */
    private boolean enabled = false;

    /**
     * 腾讯云验证码控制台分配的 AppID
     */
    private String appId;

    /**
     * 腾讯云验证码控制台分配的 AppSecretKey
     */
    private String appSecretKey;

    /**
     * 验证票据校验接口地址
     */
    private String verifyUrl = "https://ssl.captcha.qq.com/ticket/verify";

    /**
     * 调用验证接口的连接超时时间，单位毫秒
     */
    private int connectTimeoutMillis = 3000;
}
