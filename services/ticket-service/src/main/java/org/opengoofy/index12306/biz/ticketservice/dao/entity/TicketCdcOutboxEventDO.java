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

package org.opengoofy.index12306.biz.ticketservice.dao.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 票务库中仅追加的 CDC Outbox 事件。
 *
 * <p>该记录是与锁座事务一起提交的不可变事实；Canal 位点负责转发进度，表内不保存发布状态或租约。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketCdcOutboxEventDO {

    /** 主键。 */
    private Long id;

    /** 事件业务域。 */
    private String namespace;

    /** 稳定事件标识。 */
    private String eventId;

    /** 稳定事件类型。 */
    private String eventType;

    /** 业务聚合标识。 */
    private String aggregateId;

    /** 业务幂等键。 */
    private String deduplicationKey;

    /** RocketMQ 消息键。 */
    private String messageKey;

    /** 目标 Topic。 */
    private String destinationTopic;

    /** 目标 Tag。 */
    private String destinationTag;

    /** RocketMQ 延迟级别，零表示普通消息。 */
    private Integer delayLevel;

    /** 不可变消息载荷 JSON。 */
    private String payload;

    /** 事件协议版本。 */
    private Long eventVersion;

    /** 事件创建时间。 */
    private Date createdAt;
}
