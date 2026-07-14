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

package org.opengoofy.index12306.biz.gatewayservice.filter;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.gatewayservice.config.GatewayRateLimiterProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 网关层 IP 维度限流 + 自动拉黑兜底过滤器，在请求转发到下游服务之前粗筛异常流量。
 * Redis 异常时降级为放行（fail-open），避免该过滤器本身成为全站可用性的单点故障
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpRateLimiterGlobalFilter implements GlobalFilter, Ordered {

    private static final String LUA_GATEWAY_IP_RATE_LIMITER_PATH = "lua/gateway_ip_rate_limiter.lua";

    /**
     * Redis Key 前缀，${unique-name:} 用于多环境共享同一个 Redis 实例时做命名隔离
     */
    private static final String RATE_KEY_PREFIX = "index12306-gateway-ratelimiter${unique-name:}:ip:";
    private static final String VIOLATION_KEY_PREFIX = "index12306-risk${unique-name:}:violation:ip:";
    private static final String BLACKLIST_KEY_PREFIX = "index12306-risk${unique-name:}:blacklist:ip:";

    private static final RedisScript<Long> RATE_LIMITER_SCRIPT = buildScript();

    private static RedisScript<Long> buildScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_GATEWAY_IP_RATE_LIMITER_PATH)));
        script.setResultType(Long.class);
        return script;
    }

    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    private final GatewayRateLimiterProperties rateLimiterProperties;

    private final ConfigurableEnvironment environment;

    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!rateLimiterProperties.isEnabled()) {
            return chain.filter(exchange);
        }
        String clientIp = getClientIp(exchange.getRequest());
        List<String> keys = List.of(
                environment.resolvePlaceholders(RATE_KEY_PREFIX + clientIp),
                environment.resolvePlaceholders(VIOLATION_KEY_PREFIX + clientIp),
                environment.resolvePlaceholders(BLACKLIST_KEY_PREFIX + clientIp)
        );
        List<String> args = List.of(
                String.valueOf(rateLimiterProperties.getPermitsPerSecond()),
                String.valueOf(rateLimiterProperties.getViolationThreshold()),
                String.valueOf(rateLimiterProperties.getViolationWindowSeconds()),
                String.valueOf(rateLimiterProperties.getBlacklistTtlSeconds())
        );
        return reactiveStringRedisTemplate.execute(RATE_LIMITER_SCRIPT, keys, args)
                .next()
                .defaultIfEmpty(1L)
                .onErrorResume(ex -> {
                    log.warn("网关限流组件访问 Redis 异常，本次降级放行，clientIp={}", clientIp, ex);
                    return Mono.just(1L);
                })
                .flatMap(result -> {
                    if (result == 1L) {
                        return chain.filter(exchange);
                    }
                    meterRegistry.counter("gateway.ratelimiter.rejected", "reason", result == -1L ? "blacklist" : "limit").increment();
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    return response.setComplete();
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String getClientIp(ServerHttpRequest request) {
        if (rateLimiterProperties.isTrustForwardedHeader()) {
            String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }
}
