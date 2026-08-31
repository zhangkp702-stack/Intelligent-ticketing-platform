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
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.opengoofy.index12306.biz.canalrelay.retry.dao.entity.CanalRelayRetryEventDO;

import java.util.Date;
import java.util.List;

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
            + "status, attempt_count, max_attempts, next_retry_time, last_error, lease_owner, lease_until, "
            + "created_at, updated_at "
            + "FROM t_canal_relay_retry WHERE retry_key = #{retryKey} LIMIT 1")
    CanalRelayRetryEventDO selectByRetryKey(@Param("retryKey") String retryKey);

    /**
     * 查询到期且未被有效租约占用的 RETRY 任务候选。
     *
     * @param limit 本次最多读取的候选数量
     * @return 按下次重试时间排序的候选任务
     */
    @Select("SELECT id, retry_key, namespace, event_id, command_id, aggregate_id, destination_topic, "
            + "destination_tag, delay_level, payload_ciphertext, payload_fingerprint, payload_key_version, "
            + "status, attempt_count, max_attempts, next_retry_time, last_error, lease_owner, lease_until, "
            + "created_at, updated_at FROM t_canal_relay_retry "
            + "WHERE status = 'RETRY' AND next_retry_time <= NOW(3) "
            + "AND (lease_until IS NULL OR lease_until < NOW(3)) "
            + "ORDER BY next_retry_time, id LIMIT #{limit}")
    List<CanalRelayRetryEventDO> selectDue(@Param("limit") int limit);

    /**
     * 读取已抢占任务的最新快照，避免候选查询与租约更新之间使用过期尝试次数。
     *
     * @param id 任务主键
     * @return 当前任务快照，不存在时返回 null
     */
    @Select("SELECT id, retry_key, namespace, event_id, command_id, aggregate_id, destination_topic, "
            + "destination_tag, delay_level, payload_ciphertext, payload_fingerprint, payload_key_version, "
            + "status, attempt_count, max_attempts, next_retry_time, last_error, lease_owner, lease_until, "
            + "created_at, updated_at FROM t_canal_relay_retry WHERE id = #{id} LIMIT 1")
    CanalRelayRetryEventDO selectById(@Param("id") Long id);

    /**
     * 以状态、时间和租约条件原子抢占一条任务，避免多实例重复执行。
     *
     * @param id 任务主键
     * @param owner 当前 Relay 实例标识
     * @param leaseUntil 本次租约过期时间
     * @return 抢占成功返回一，已被其他实例抢占返回零
     */
    @Update("UPDATE t_canal_relay_retry SET lease_owner = #{owner}, lease_until = #{leaseUntil}, "
            + "updated_at = NOW(3) WHERE id = #{id} AND status = 'RETRY' AND next_retry_time <= NOW(3) "
            + "AND (lease_until IS NULL OR lease_until < NOW(3))")
    int claim(@Param("id") Long id, @Param("owner") String owner, @Param("leaseUntil") Date leaseUntil);

    /**
     * 删除当前租约持有者已经成功投递的任务。
     *
     * @param id 任务主键
     * @param owner 当前 Relay 实例标识
     * @return 删除成功返回一，租约已失效返回零
     */
    @Delete("DELETE FROM t_canal_relay_retry WHERE id = #{id} AND status = 'RETRY' "
            + "AND lease_owner = #{owner}")
    int deleteClaimed(@Param("id") Long id, @Param("owner") String owner);

    /**
     * 记录一次重试结果并释放租约；达到上限时转为 DEAD。
     *
     * @param id 任务主键
     * @param owner 当前 Relay 实例标识
     * @param status 更新后的任务状态
     * @param attemptCount 累计发送尝试次数
     * @param nextRetryTime 下一次允许重试的时间
     * @param lastError 最近一次失败摘要
     * @return 更新成功返回一，租约已失效返回零
     */
    @Update("UPDATE t_canal_relay_retry SET status = #{status}, attempt_count = #{attemptCount}, "
            + "next_retry_time = #{nextRetryTime}, last_error = #{lastError}, lease_owner = NULL, "
            + "lease_until = NULL, updated_at = NOW(3) WHERE id = #{id} AND status = 'RETRY' "
            + "AND lease_owner = #{owner}")
    int releaseAfterFailure(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("status") String status,
            @Param("attemptCount") int attemptCount,
            @Param("nextRetryTime") Date nextRetryTime,
            @Param("lastError") String lastError);
}
