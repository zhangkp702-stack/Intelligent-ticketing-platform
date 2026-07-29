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

package org.opengoofy.index12306.biz.ticketservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.BusinessOperationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.BusinessOperationMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 使用独立短事务认领和更新业务操作，确保购票主事务之外仍保留幂等状态。
 */
@Service
@RequiredArgsConstructor
public class BusinessOperationTransactionService {

    public static final int STATUS_PROCESSING = 0;
    public static final int STATUS_SUCCEEDED = 1;
    public static final int STATUS_FAILED = 2;

    private final BusinessOperationMapper businessOperationMapper;

    /**
     * 在独立事务中认领业务操作。
     *
     * @param operation 待认领的操作记录
     * @return 插入成功返回 true，操作标识已经存在返回 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(BusinessOperationDO operation) {
        try {
            // 主键唯一约束负责在多实例并发时只允许一个请求获得执行权。
            businessOperationMapper.insert(operation);
            return true;
        } catch (DuplicateKeyException ignored) {
            // 重复请求交由上层读取原状态，当前短事务不再执行其他写操作。
            return false;
        }
    }

    /**
     * 查询已经持久化的业务操作。
     *
     * @param operationId 操作标识
     * @return 操作记录，不存在时返回 null
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public BusinessOperationDO findById(String operationId) {
        // 独立读事务确保重复插入等待结束后可以读取首个请求已经提交的状态。
        return businessOperationMapper.selectById(operationId);
    }

    /**
     * 将处理中操作更新为成功，并保存可供重复请求复用的原始结果。
     *
     * @param operationId 操作标识
     * @param resultJson 成功结果 JSON
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(String operationId, String resultJson) {
        // 只允许处理中状态完成一次，避免后到请求覆盖已经确定的结果。
        int updated = businessOperationMapper.update(
                null,
                Wrappers.<BusinessOperationDO>lambdaUpdate()
                        .eq(BusinessOperationDO::getOperationId, operationId)
                        .eq(BusinessOperationDO::getStatus, STATUS_PROCESSING)
                        .set(BusinessOperationDO::getStatus, STATUS_SUCCEEDED)
                        .set(BusinessOperationDO::getResultJson, resultJson)
                        .set(BusinessOperationDO::getFailureMessage, null)
                        .set(BusinessOperationDO::getUpdateTime, new Date()));
        requireSingleUpdate(operationId, updated);
    }

    /**
     * 将处理中操作更新为失败，阻止相同操作标识再次扣票。
     *
     * @param operationId 操作标识
     * @param failureMessage 失败原因摘要
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String operationId, String failureMessage) {
        // 失败状态同样是终态；调用方需要使用新的操作标识发起新的业务尝试。
        int updated = businessOperationMapper.update(
                null,
                Wrappers.<BusinessOperationDO>lambdaUpdate()
                        .eq(BusinessOperationDO::getOperationId, operationId)
                        .eq(BusinessOperationDO::getStatus, STATUS_PROCESSING)
                        .set(BusinessOperationDO::getStatus, STATUS_FAILED)
                        .set(BusinessOperationDO::getFailureMessage, failureMessage)
                        .set(BusinessOperationDO::getUpdateTime, new Date()));
        requireSingleUpdate(operationId, updated);
    }

    /**
     * 校验幂等状态仅发生一次有效迁移。
     *
     * @param operationId 操作标识
     * @param updated 实际更新行数
     */
    private void requireSingleUpdate(String operationId, int updated) {
        if (updated != 1) {
            throw new IllegalStateException("业务操作状态更新失败: " + operationId);
        }
    }
}
