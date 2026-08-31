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

package org.opengoofy.index12306.biz.canalrelay.retry.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 待持久化的 Relay 单条失败任务。
 *
 * <p>该对象暂时携带内存中的明文载荷，只允许在事务服务内部转换为加密列；不得直接打印或跨服务传递。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanalRelayRetryEvent {

    /** 稳定事件级唯一键。 */
    private String retryKey;

    /** 事件业务域。 */
    private String namespace;

    /** Outbox 事件标识。 */
    private String eventId;

    /** 订单创建命令标识。 */
    private String commandId;

    /** 业务聚合标识。 */
    private String aggregateId;

    /** RocketMQ 目标 Topic。 */
    private String destinationTopic;

    /** RocketMQ 目标 Tag。 */
    private String destinationTag;

    /** RocketMQ 延迟级别。 */
    private Integer delayLevel;

    /** 尚未加密的消息载荷，仅在事务转换阶段短暂存在。 */
    private String payload;

    /** 当前失败任务状态。 */
    private CanalRelayRetryStatus status;

    /** 已经执行的发送尝试次数。 */
    private Integer attemptCount;

    /** 允许自动发送的最大次数。 */
    private Integer maxAttempts;

    /** 下次允许重试的时间。 */
    private Date nextRetryTime;

    /** 限长且脱敏的最近失败摘要。 */
    private String lastError;
}
