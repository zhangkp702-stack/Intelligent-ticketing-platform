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

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.captcha.annotation.RiskGuard;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.ratelimiter.config.RateLimiterProperties;
import org.opengoofy.index12306.framework.starter.ratelimiter.core.RequestRiskKeyResolver;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 风险触发式验证码校验 AOP 拦截器，对标注 {@link RiskGuard} 的方法生效。
 * 查询嫌疑名单标记本身依赖 Redis，Redis 异常时降级为放行（fail-open），不应反过来拦截全部正常请求
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public final class RiskGuardAspect {

    private static final String CAPTCHA_TICKET_HEADER = "Captcha-Ticket";
    private static final String CAPTCHA_RANDSTR_HEADER = "Captcha-Randstr";

    private final CaptchaVerifyService captchaVerifyService;

    private final DistributedCache distributedCache;

    private final RateLimiterProperties rateLimiterProperties;

    private final ConfigurableEnvironment environment;

    private final MeterRegistry meterRegistry;

    @Around("@annotation(org.opengoofy.index12306.framework.starter.captcha.annotation.RiskGuard)")
    public Object riskGuardHandler(ProceedingJoinPoint joinPoint) throws Throwable {
        RiskGuard riskGuard = getRiskGuard(joinPoint);
        HttpServletRequest request = getRequest();
        String dimensionValue = RequestRiskKeyResolver.resolveDimensionValue(
                riskGuard.dimension(), request, rateLimiterProperties.isTrustForwardedHeader());
        String suspectKey = RequestRiskKeyResolver.buildSuspectKey(dimensionValue, environment);
        if (isSuspect(suspectKey)) {
            String ticket = request.getHeader(CAPTCHA_TICKET_HEADER);
            String randstr = request.getHeader(CAPTCHA_RANDSTR_HEADER);
            String clientIp = RequestRiskKeyResolver.getClientIp(request, rateLimiterProperties.isTrustForwardedHeader());
            if (!captchaVerifyService.verify(ticket, randstr, clientIp)) {
                meterRegistry.counter("captcha.verify.failed").increment();
                throw new ClientException(riskGuard.message());
            }
            meterRegistry.counter("captcha.verify.success").increment();
            clearSuspect(suspectKey);
        }
        return joinPoint.proceed();
    }

    private boolean isSuspect(String suspectKey) {
        try {
            return Boolean.TRUE.equals(distributedCache.hasKey(suspectKey));
        } catch (Exception ex) {
            log.warn("风控嫌疑名单查询 Redis 异常，本次降级为非嫌疑，suspectKey={}", suspectKey, ex);
            return false;
        }
    }

    private void clearSuspect(String suspectKey) {
        try {
            distributedCache.delete(suspectKey);
        } catch (Exception ex) {
            log.warn("清除风控嫌疑名单标记失败，suspectKey={}", suspectKey, ex);
        }
    }

    private RiskGuard getRiskGuard(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(RiskGuard.class);
    }

    private HttpServletRequest getRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes.getRequest();
    }
}
