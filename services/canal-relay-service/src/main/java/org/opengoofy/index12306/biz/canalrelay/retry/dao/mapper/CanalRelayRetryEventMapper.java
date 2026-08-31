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

package org.opengoofy.index12306.biz.canalrelay.retry.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.biz.canalrelay.retry.dao.entity.CanalRelayRetryEventDO;

/**
 * Relay 失败任务持久层。
 */
public interface CanalRelayRetryEventMapper {

    /**
     * 按 retryKey 幂等插入失败任务，避免 Canal 批次重放创建重复记录。
     *
     * @param event 待保存的加密失败任务
     * @return 新增一条记录返回一，命中唯一键返回零
     */
    @Insert("INSERT IGNORE INTO t_canal_relay_retry "
            + "(retry_key, namespace, event_id, command_id, aggregate_id, destination_topic, "
            + "destination_tag, delay_level, payload_ciphertext, payload_fingerprint, payload_key_version, "
            + "status, attempt_count, max_attempts, next_retry_time, last_error, created_at, updated_at) "
            + "VALUES (#{event.retryKey}, #{event.namespace}, #{event.eventId}, #{event.commandId}, "
            + "#{event.aggregateId}, #{event.destinationTopic}, #{event.destinationTag}, #{event.delayLevel}, "
            + "#{event.payloadCiphertext}, #{event.payloadFingerprint}, #{event.payloadKeyVersion}, "
            + "#{event.status}, #{event.attemptCount}, #{event.maxAttempts}, #{event.nextRetryTime}, "
            + "#{event.lastError}, #{event.createdAt}, #{event.updatedAt})")
    int insertIgnore(@Param("event") CanalRelayRetryEventDO event);

    /**
     * 读取唯一键对应的已有任务，供重复批次进行不可变字段校验。
     *
     * @param retryKey 稳定事件级唯一键
     * @return 已有记录，不存在时返回 null
     */
    @Select("SELECT id, retry_key, namespace, event_id, command_id, aggregate_id, destination_topic, "
            + "destination_tag, delay_level, payload_ciphertext, payload_fingerprint, payload_key_version, "
            + "status, attempt_count, max_attempts, next_retry_time, last_error, created_at, updated_at "
            + "FROM t_canal_relay_retry WHERE retry_key = #{retryKey} LIMIT 1")
    CanalRelayRetryEventDO selectByRetryKey(@Param("retryKey") String retryKey);
}
