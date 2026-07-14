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

package org.opengoofy.index12306.framework.starter.captcha.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.framework.starter.captcha.config.TencentCaptchaProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 腾讯云验证码（免费版）校验实现，调用腾讯云验证票据核验接口。
 * 接口字段以腾讯云验证码控制台当前文档为准，若官方接口字段调整需同步调整本类
 */
@Slf4j
@RequiredArgsConstructor
public final class TencentCaptchaVerifyServiceImpl implements CaptchaVerifyService {

    private static final int SUCCESS_CODE = 1;

    private final TencentCaptchaProperties properties;

    @Override
    public boolean verify(String ticket, String randstr, String clientIp) {
        if (!properties.isEnabled()) {
            return true;
        }
        if (StrUtil.hasBlank(ticket, randstr)) {
            return false;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("aid", properties.getAppId());
        params.put("AppSecretKey", properties.getAppSecretKey());
        params.put("Ticket", ticket);
        params.put("Randstr", randstr);
        params.put("UserIP", clientIp);
        try {
            String responseBody = HttpUtil.get(properties.getVerifyUrl(), params, properties.getConnectTimeoutMillis());
            JSONObject json = JSONUtil.parseObj(responseBody);
            return json.getInt("response", 0) == SUCCESS_CODE;
        } catch (Exception ex) {
            log.warn("调用腾讯云验证码核验接口失败, ticket={}, clientIp={}", ticket, clientIp, ex);
            return false;
        }
    }
}
