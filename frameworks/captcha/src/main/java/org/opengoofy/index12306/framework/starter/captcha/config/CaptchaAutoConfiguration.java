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

import io.micrometer.core.instrument.MeterRegistry;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.captcha.core.CaptchaVerifyService;
import org.opengoofy.index12306.framework.starter.captcha.core.RiskGuardAspect;
import org.opengoofy.index12306.framework.starter.captcha.core.TencentCaptchaVerifyServiceImpl;
import org.opengoofy.index12306.framework.starter.ratelimiter.config.RateLimiterProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 验证码风控自动装配
 */
@EnableConfigurationProperties(TencentCaptchaProperties.class)
public class CaptchaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CaptchaVerifyService captchaVerifyService(TencentCaptchaProperties properties) {
        return new TencentCaptchaVerifyServiceImpl(properties);
    }

    @Bean
    public RiskGuardAspect riskGuardAspect(CaptchaVerifyService captchaVerifyService,
                                           DistributedCache distributedCache,
                                           RateLimiterProperties rateLimiterProperties,
                                           ConfigurableEnvironment environment,
                                           MeterRegistry meterRegistry) {
        return new RiskGuardAspect(captchaVerifyService, distributedCache, rateLimiterProperties, environment, meterRegistry);
    }
}
