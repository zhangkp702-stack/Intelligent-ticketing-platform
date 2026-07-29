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

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.BusinessOperationDO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_FAILED;
import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_PROCESSING;
import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_SUCCEEDED;

/**
 * 统一协调票务写操作的数据库认领、结果重放和终态持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessOperationCoordinator {

    private static final int OPERATION_ID_MAX_LENGTH = 64;
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 500;

    private final BusinessOperationTransactionService operationTransactionService;

    /**
     * 清理并校验调用方提供的操作标识。
     *
     * @param operationId 原始操作标识
     * @return 规范化操作标识，未提供时返回 null
     */
    public String normalizeOperationId(String operationId) {
        if (StrUtil.isBlank(operationId)) {
            return null;
        }
        String normalized = operationId.trim();
        if (normalized.length() > OPERATION_ID_MAX_LENGTH) {
            throw new ServiceException("业务操作标识长度不能超过 64 个字符");
        }
        return normalized;
    }

    /**
     * 执行已经携带稳定操作标识的票务写操作。
     *
     * @param operationId 已规范化的操作标识
     * @param operationType 稳定业务操作类型
     * @param fingerprintPayload 不包含操作标识的业务参数
     * @param resultType 结果反序列化类型
     * @param operation 只有获得执行权后才允许调用的真实写操作
     * @return 首次执行结果，或重复成功操作保存的原结果
     * @param <T> 业务结果类型
     */
    public <T> T execute(
            String operationId,
            String operationType,
            Object fingerprintPayload,
            Class<T> resultType,
            Supplier<T> operation) {
        Objects.requireNonNull(operationId, "操作标识不能为空");
        Objects.requireNonNull(operationType, "操作类型不能为空");
        Objects.requireNonNull(fingerprintPayload, "业务参数不能为空");
        Objects.requireNonNull(resultType, "结果类型不能为空");
        Objects.requireNonNull(operation, "业务操作不能为空");

        // 操作记录同时绑定用户、业务类型和参数摘要，禁止跨用户或跨业务复用操作标识。
        String userId = requireCurrentUserId();
        String requestFingerprint = fingerprint(fingerprintPayload);
        BusinessOperationDO operationRecord = BusinessOperationDO.builder()
                .operationId(operationId)
                .operationType(operationType)
                .userId(userId)
                .requestFingerprint(requestFingerprint)
                .status(STATUS_PROCESSING)
                .build();
        if (!operationTransactionService.tryClaim(operationRecord)) {
            return resolveExistingOperation(
                    operationId,
                    operationType,
                    userId,
                    requestFingerprint,
                    resultType);
        }

        T result;
        try {
            // 只有成功认领唯一键的请求可以进入真实票务写链路。
            result = Objects.requireNonNull(operation.get(), "业务操作结果不能为空");
        } catch (RuntimeException exception) {
            markFailed(operationId, exception);
            throw exception;
        }

        // 写操作提交后在独立事务中固化结果，供相同操作标识安全重放。
        operationTransactionService.markSucceeded(operationId, JSON.toJSONString(result));
        return result;
    }

    /**
     * 解析已经存在的操作状态，并在成功时返回保存的原始结果。
     *
     * @param operationId 操作标识
     * @param operationType 当前业务操作类型
     * @param userId 当前用户标识
     * @param requestFingerprint 当前业务参数摘要
     * @param resultType 结果反序列化类型
     * @return 已成功操作保存的业务结果
     * @param <T> 业务结果类型
     */
    private <T> T resolveExistingOperation(
            String operationId,
            String operationType,
            String userId,
            String requestFingerprint,
            Class<T> resultType) {
        BusinessOperationDO existing = operationTransactionService.findById(operationId);
        if (existing == null) {
            throw new ServiceException("业务操作状态暂不可用，请稍后重试");
        }

        // 操作标识必须始终属于同一用户、同一业务类型和同一份不可变业务参数。
        if (!operationType.equals(existing.getOperationType())
                || !userId.equals(existing.getUserId())
                || !requestFingerprint.equals(existing.getRequestFingerprint())) {
            throw new ServiceException("业务操作标识与原请求不一致");
        }
        if (Objects.equals(existing.getStatus(), STATUS_SUCCEEDED)) {
            return JSON.parseObject(existing.getResultJson(), resultType);
        }
        if (Objects.equals(existing.getStatus(), STATUS_PROCESSING)) {
            throw new ServiceException("业务操作正在处理中，请稍后查询结果");
        }
        if (Objects.equals(existing.getStatus(), STATUS_FAILED)) {
            throw new ServiceException("该业务操作此前执行失败，请使用新的操作标识重试");
        }
        throw new ServiceException("业务操作状态无效");
    }

    /**
     * 在真实写操作失败后持久化失败终态，同时保留原业务异常。
     *
     * @param operationId 操作标识
     * @param exception 原业务异常
     */
    private void markFailed(String operationId, RuntimeException exception) {
        try {
            // 失败摘要只用于运维定位，不持久化完整异常堆栈或请求敏感字段。
            operationTransactionService.markFailed(operationId, safeFailureMessage(exception));
        } catch (RuntimeException persistenceException) {
            log.error("业务操作失败状态持久化异常，operationId={}", operationId, persistenceException);
            exception.addSuppressed(persistenceException);
        }
    }

    /**
     * 读取当前经过网关或 MCP 身份传递的用户标识。
     *
     * @return 当前用户标识
     */
    private String requireCurrentUserId() {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(userId)) {
            throw new ServiceException("当前用户身份不存在");
        }
        return userId.trim();
    }

    /**
     * 计算稳定业务参数摘要。
     *
     * @param payload 不包含操作标识的业务参数
     * @return SHA-256 十六进制摘要
     */
    private String fingerprint(Object payload) {
        return sha256(JSON.toJSONString(payload));
    }

    /**
     * 生成 SHA-256 摘要。
     *
     * @param value 待摘要文本
     * @return 小写十六进制摘要
     */
    private String sha256(String value) {
        try {
            // JDK 标准实现避免为单一摘要算法引入额外依赖。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * 把异常转换为长度受限且不包含堆栈的失败摘要。
     *
     * @param exception 业务异常
     * @return 可持久化失败摘要
     */
    private String safeFailureMessage(RuntimeException exception) {
        String message = StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getSimpleName());
        return message.length() <= FAILURE_MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, FAILURE_MESSAGE_MAX_LENGTH);
    }
}
