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

package org.opengoofy.index12306.framework.starter.idempotent.annotation;

import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentTypeEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 基于 Redis 的瞬时重复拦截注解。
 *
 * <p>该注解只用于降低短时间内的重复提交或重复投递，不保存业务结果，也不能覆盖
 * "真实副作用已成功但进程在响应前退出" 的故障窗口。需要服务端命令标识、结果重放、
 * 租约围栏和 UNKNOWN 对账的远程写操作，应使用 reliable-command 的
 * {@code @ReliableCommandExecution}，并由业务模块提供后续对账。</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 幂等 Key，只有在 {@link Idempotent#type()} 为 {@link IdempotentTypeEnum#SPEL} 时生效。
     * 此 Key 仅作为 Redis 锁或缓存标识，不能作为跨服务业务操作的权威身份。
     */
    String key() default "";

    /**
     * 触发瞬时重复拦截时返回的错误提示信息。
     */
    String message() default "您操作太快，请稍后再试";

    /**
     * 验证瞬时重复拦截类型，支持多种 Redis 实现。
     * RestAPI 建议使用 {@link IdempotentTypeEnum#TOKEN} 或 {@link IdempotentTypeEnum#PARAM}
     * 其它类型幂等验证，使用 {@link IdempotentTypeEnum#SPEL}
     */
    IdempotentTypeEnum type() default IdempotentTypeEnum.PARAM;

    /**
     * 验证幂等场景，支持多种 {@link IdempotentSceneEnum}
     */
    IdempotentSceneEnum scene() default IdempotentSceneEnum.RESTAPI;

    /**
     * 设置防重令牌 Key 前缀，MQ 幂等去重可选设置
     * {@link IdempotentSceneEnum#MQ} and {@link IdempotentTypeEnum#SPEL} 时生效
     */
    String uniqueKeyPrefix() default "";

    /**
     * 设置防重令牌 Key 过期时间，单位秒，默认 1 小时，MQ 幂等去重可选设置
     * {@link IdempotentSceneEnum#MQ} and {@link IdempotentTypeEnum#SPEL} 时生效
     */
    long keyTimeout() default 3600L;
}
