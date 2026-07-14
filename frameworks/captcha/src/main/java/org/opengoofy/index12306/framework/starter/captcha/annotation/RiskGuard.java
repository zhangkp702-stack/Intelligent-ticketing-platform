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

package org.opengoofy.index12306.framework.starter.captcha.annotation;

import org.opengoofy.index12306.framework.starter.ratelimiter.enums.RateLimitDimensionEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 风险触发式验证码校验注解。当标注方法对应的用户/IP 维度被
 * {@code @RateLimiter} 标记为风控嫌疑名单时，本次请求必须携带有效的验证码票据
 * （请求头 Captcha-Ticket / Captcha-Randstr）才能放行，验证通过后解除嫌疑标记
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RiskGuard {

    /**
     * 风控维度，需要与同一方法上 {@code @RateLimiter} 的维度保持一致
     */
    RateLimitDimensionEnum dimension() default RateLimitDimensionEnum.USER_THEN_IP;

    /**
     * 触发验证码校验但未通过时返回的错误提示信息
     */
    String message() default "请完成安全验证后重试";
}
