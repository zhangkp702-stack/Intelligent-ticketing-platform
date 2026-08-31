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

package org.opengoofy.index12306.biz.ticketservice.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TicketCdcOutboxEventDO;

/**
 * 票务 CDC Outbox 持久层。
 */
public interface TicketCdcOutboxEventMapper {

    /**
     * 利用数据库唯一键幂等追加一条 CDC 事件。
     *
     * @param event 与本地业务事务一起提交的不可变事件
     * @return 插入新记录时返回一，复用已有业务幂等键时返回零
     */
    @Insert("INSERT IGNORE INTO t_cdc_outbox_event "
            + "(namespace, event_id, event_type, aggregate_id, deduplication_key, message_key, "
            + "destination_topic, destination_tag, delay_level, payload, event_version, created_at) "
            + "VALUES (#{event.namespace}, #{event.eventId}, #{event.eventType}, #{event.aggregateId}, "
            + "#{event.deduplicationKey}, #{event.messageKey}, #{event.destinationTopic}, "
            + "#{event.destinationTag}, #{event.delayLevel}, #{event.payload}, #{event.eventVersion}, "
            + "#{event.createdAt})")
    int insertIgnore(@Param("event") TicketCdcOutboxEventDO event);

    /**
     * 按事件域和业务幂等键读取已经提交的不可变事件。
     *
     * @param namespace 事件业务域
     * @param deduplicationKey 业务幂等键
     * @return 已存在事件，不存在时返回 null
     */
    @Select("SELECT id, namespace, event_id, event_type, aggregate_id, deduplication_key, message_key, "
            + "destination_topic, destination_tag, delay_level, payload, event_version, created_at "
            + "FROM t_cdc_outbox_event WHERE namespace = #{namespace} "
            + "AND deduplication_key = #{deduplicationKey} LIMIT 1")
    TicketCdcOutboxEventDO selectByNamespaceAndDeduplicationKey(
            @Param("namespace") String namespace,
            @Param("deduplicationKey") String deduplicationKey);
}
