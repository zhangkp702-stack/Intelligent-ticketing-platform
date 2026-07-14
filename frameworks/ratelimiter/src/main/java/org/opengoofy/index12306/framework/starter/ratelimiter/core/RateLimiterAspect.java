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

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.ratelimiter.annotation.RateLimiter;
import org.opengoofy.index12306.framework.starter.ratelimiter.config.RateLimiterProperties;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 接口限流注解 AOP 拦截器，对标注 {@link RateLimiter} 的方法做固定窗口限流。
 * 当某个用户/IP 的请求量逼近限流阈值时，会顺带标记一个风控"嫌疑名单"标记，
 * 供 {@code captcha} 模块的风控校验复用，要求其下一次请求完成验证码校验
 */
@Aspect
@RequiredArgsConstructor
public final class RateLimiterAspect {

    /**
     * 限流计数 Key 前缀，${unique-name:} 用于多环境共享同一个 Redis 实例时做命名隔离
     */
    private static final String LIMIT_KEY_PREFIX = "index12306-ratelimiter${unique-name:}:";

    private final RedisRateLimiterExecutor rateLimiterExecutor;

    private final RateLimiterProperties rateLimiterProperties;

    private final ConfigurableEnvironment environment;

    private final MeterRegistry meterRegistry;

    @Around("@annotation(org.opengoofy.index12306.framework.starter.ratelimiter.annotation.RateLimiter)")
    public Object rateLimitHandler(ProceedingJoinPoint joinPoint) throws Throwable {
        RateLimiter rateLimiter = getRateLimiter(joinPoint);
        HttpServletRequest request = getRequest();
        String dimensionValue = RequestRiskKeyResolver.resolveDimensionValue(
                rateLimiter.dimension(), request, rateLimiterProperties.isTrustForwardedHeader());
        String limitKey = environment.resolvePlaceholders(LIMIT_KEY_PREFIX + request.getServletPath() + ":" + dimensionValue);
        String suspectKey = RequestRiskKeyResolver.buildSuspectKey(dimensionValue, environment);
        if (!rateLimiterExecutor.tryAcquire(limitKey, suspectKey, rateLimiter.permitsPerSecond())) {
            meterRegistry.counter("ratelimiter.rejected", "path", request.getServletPath(), "dimension", rateLimiter.dimension().name()).increment();
            throw new ClientException(rateLimiter.message());
        }
        return joinPoint.proceed();
    }

    private RateLimiter getRateLimiter(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(RateLimiter.class);
    }

    private HttpServletRequest getRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes.getRequest();
    }
}
