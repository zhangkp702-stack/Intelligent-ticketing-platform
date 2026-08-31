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

package org.opengoofy.index12306.biz.orderservice.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCdcOutboxEventDO;

/**
 * 订单 CDC Outbox 分表持久层。
 */
public interface OrderCdcOutboxEventMapper {

    /**
     * 利用唯一键幂等追加一条订单 CDC 事件。
     *
     * @param event 与订单事务同库提交的不可变事件
     * @return 新建事件返回一，已有业务幂等键返回零
     */
    @Insert("INSERT IGNORE INTO t_cdc_outbox_event "
            + "(user_id, command_id, recovery_shard, namespace, event_id, event_type, aggregate_id, "
            + "deduplication_key, message_key, destination_topic, destination_tag, delay_level, payload, "
            + "event_version, created_at) VALUES (#{event.userId}, #{event.commandId}, #{event.recoveryShard}, "
            + "#{event.namespace}, #{event.eventId}, #{event.eventType}, #{event.aggregateId}, "
            + "#{event.deduplicationKey}, #{event.messageKey}, #{event.destinationTopic}, "
            + "#{event.destinationTag}, #{event.delayLevel}, #{event.payload}, #{event.eventVersion}, "
            + "#{event.createdAt})")
    int insertIgnore(@Param("event") OrderCdcOutboxEventDO event);

    /**
     * 按完整分片路由与业务幂等键读取已经提交的事件。
     *
     * @param userId 用户分库键
     * @param commandId 稳定建单命令标识
     * @param recoveryShard 物理恢复分片
     * @param namespace 事件业务域
     * @param deduplicationKey 业务幂等键
     * @return 已存在事件，不存在时返回 null
     */
    @Select("SELECT id, user_id, command_id, recovery_shard, namespace, event_id, event_type, aggregate_id, "
            + "deduplication_key, message_key, destination_topic, destination_tag, delay_level, payload, "
            + "event_version, created_at FROM t_cdc_outbox_event "
            + "WHERE user_id = #{userId} AND command_id = #{commandId} "
            + "AND recovery_shard = #{recoveryShard} AND namespace = #{namespace} "
            + "AND deduplication_key = #{deduplicationKey} LIMIT 1")
    OrderCdcOutboxEventDO selectByRoutingAndDeduplicationKey(
            @Param("userId") String userId,
            @Param("commandId") String commandId,
            @Param("recoveryShard") Integer recoveryShard,
            @Param("namespace") String namespace,
            @Param("deduplicationKey") String deduplicationKey);
}
