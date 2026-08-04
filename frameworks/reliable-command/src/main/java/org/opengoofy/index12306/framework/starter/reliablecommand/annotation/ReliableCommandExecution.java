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

package org.opengoofy.index12306.framework.starter.reliablecommand.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将同步的远程写副作用接入可靠命令状态机。
 *
 * <p>所有表达式使用 Spring SpEL，可通过 {@code #p0}、{@code #a0} 或已保留的参数名取值。
 * 被标记的方法必须处于参数校验和用户确认之后，并且只在真正调用远程写服务的边界使用。
 * 它不适用于响应式流、异步任务、本地事务写入或 MQ 消费；这些场景分别需要其自身的
 * 事务、可靠事件或 Inbox 状态机。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReliableCommandExecution {

    /**
     * 可靠命令命名空间，按业务动作而非接口 URL 划分。
     *
     * @return 不包含用户敏感信息的稳定命名空间
     */
    String namespace();

    /**
     * 生成服务端稳定命令标识的 SpEL 表达式。
     *
     * @return 同一业务意图重试时不变的命令标识表达式
     */
    String commandId();

    /**
     * 生成数据库路由键的 SpEL 表达式。
     *
     * @return 用于本地分库路由且同一命令保持不变的表达式
     */
    String routingKey();

    /**
     * 生成命令所属主体的 SpEL 表达式。
     *
     * @return 用户、租户或可信消费者身份表达式
     */
    String ownerId();

    /**
     * 指定参与请求摘要的 SpEL 表达式；留空时使用全部方法参数。
     *
     * @return 请求摘要载荷表达式
     */
    String fingerprint() default "";

    /**
     * 指定可安全记录的业务引用 SpEL 表达式；留空时不保存引用。
     *
     * @return 订单号等非敏感业务引用表达式
     */
    String businessReference() default "";

    /**
     * 指定稳定命令类型；留空时由目标类和方法名生成。
     *
     * @return 不超过持久化字段长度的命令类型
     */
    String commandType() default "";
}
