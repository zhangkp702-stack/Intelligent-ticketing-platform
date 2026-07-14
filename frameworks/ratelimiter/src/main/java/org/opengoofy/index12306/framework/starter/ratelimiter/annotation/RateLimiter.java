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

package org.opengoofy.index12306.framework.starter.ratelimiter.annotation;

import org.opengoofy.index12306.framework.starter.ratelimiter.enums.RateLimitDimensionEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解，基于 Redis 固定窗口计数器实现，每个自然秒重置一次
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    /**
     * 每秒允许通过的请求数
     */
    int permitsPerSecond();

    /**
     * 限流维度，决定计数 Key 按用户还是按 IP 区分
     */
    RateLimitDimensionEnum dimension() default RateLimitDimensionEnum.USER_THEN_IP;

    /**
     * 触发限流时返回的错误提示信息
     */
    String message() default "请求过于频繁，请稍后再试";
}
