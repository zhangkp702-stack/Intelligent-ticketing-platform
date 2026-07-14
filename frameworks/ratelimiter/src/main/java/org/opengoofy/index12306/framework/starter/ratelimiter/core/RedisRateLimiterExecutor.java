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

import cn.hutool.core.lang.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;
import java.util.Objects;

/**
 * 基于 Redis 固定窗口计数器的限流执行器，每个自然秒重置一次计数；
 * 用量逼近阈值时顺带写入风控嫌疑名单标记。
 * Redis 不可用时降级为放行（fail-open），避免限流组件本身成为购票链路的可用性故障点
 */
@Slf4j
@RequiredArgsConstructor
public final class RedisRateLimiterExecutor {

    private static final String LUA_FIXED_WINDOW_RATE_LIMITER_PATH = "lua/fixed_window_rate_limiter.lua";

    /**
     * 嫌疑名单标记的存活时间，期间该维度的下一次请求需要完成验证码校验
     */
    private static final long SUSPECT_FLAG_TTL_SECONDS = 600L;

    private final DistributedCache distributedCache;

    /**
     * 尝试获取一次许可
     *
     * @param key              限流计数 Key
     * @param suspectKey       嫌疑名单标记 Key
     * @param permitsPerSecond 每秒允许通过的请求数
     * @return 是否允许通过；Redis 异常时直接放行
     */
    public boolean tryAcquire(String key, String suspectKey, int permitsPerSecond) {
        try {
            DefaultRedisScript<Long> script = Singleton.get(LUA_FIXED_WINDOW_RATE_LIMITER_PATH, () -> {
                DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_FIXED_WINDOW_RATE_LIMITER_PATH)));
                redisScript.setResultType(Long.class);
                return redisScript;
            });
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            Long result = stringRedisTemplate.execute(script, List.of(key, suspectKey),
                    String.valueOf(permitsPerSecond), String.valueOf(SUSPECT_FLAG_TTL_SECONDS));
            return Objects.equals(result, 1L);
        } catch (Exception ex) {
            log.warn("限流组件访问 Redis 异常，本次降级放行，key={}", key, ex);
            return true;
        }
    }
}
