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

package org.opengoofy.index12306.biz.canalrelay.retry.dao.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Relay 失败重试任务的加密持久化记录。
 *
 * <p>数据库只保存 payload 密文和摘要，后续发布器必须在受控的重试流程内解密。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_canal_relay_retry")
public class CanalRelayRetryEventDO {

    /** 数据库主键。 */
    @TableId
    private Long id;

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

    /** AES-GCM 保护后的消息载荷。 */
    private String payloadCiphertext;

    /** 明文载荷 SHA-256 摘要，用于重复事件冲突校验。 */
    private String payloadFingerprint;

    /** 加密密钥版本。 */
    private Integer payloadKeyVersion;

    /** 失败任务状态：RETRY 或 DEAD。 */
    private String status;

    /** 已经执行的发送尝试次数。 */
    private Integer attemptCount;

    /** 允许自动发送的最大次数。 */
    private Integer maxAttempts;

    /** 下次允许重试的时间。 */
    private Date nextRetryTime;

    /** 限长且脱敏的最近失败摘要。 */
    private String lastError;

    /** 首次登记时间。 */
    private Date createdAt;

    /** 最近修改时间。 */
    private Date updatedAt;
}
