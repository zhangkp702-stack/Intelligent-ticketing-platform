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
import org.opengoofy.index12306.biz.ticketservice.dto.resp.BusinessOperationStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationLeaseService.OperationLease;

import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_FAILED;
import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_PROCESSING;
import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_SUCCEEDED;
import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_UNKNOWN;
import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_RECONCILING;
import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_MANUAL_REVIEW;

/**
 * 统一协调票务写操作的数据库认领、结果重放和终态持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessOperationCoordinator {

    private static final int OPERATION_ID_MAX_LENGTH = 64;
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 500;
    private static final String PURCHASE_OPERATION_TYPE = "PURCHASE_TICKET";
    private static final String CANCELLATION_OPERATION_TYPE = "CANCEL_TICKET_ORDER";
    private static final String REFUND_OPERATION_TYPE = "REFUND_TICKET";

    private final BusinessOperationTransactionService operationTransactionService;
    private final BusinessOperationLeaseService operationLeaseService;

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
     * @param initialBusinessReference 取消或退票已知的订单号，购票时为空
     * @param resultType 结果反序列化类型
     * @param operation 只有获得执行权后才允许调用的真实写操作
     * @return 首次执行结果，或重复成功操作保存的原结果
     * @param <T> 业务结果类型
     */
    public <T> T execute(
            String operationId,
            String operationType,
            Object fingerprintPayload,
            String initialBusinessReference,
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
        OperationLease lease = operationLeaseService.create(operationId);
        BusinessOperationDO operationRecord = BusinessOperationDO.builder()
                .operationId(operationId)
                .operationType(operationType)
                .userId(userId)
                .requestFingerprint(requestFingerprint)
                .status(STATUS_PROCESSING)
                .leaseOwner(lease.owner())
                .leaseUntil(lease.leaseUntil())
                .executionEpoch(lease.epoch())
                .lastHeartbeatAt(lease.heartbeatAt())
                .businessReference(initialBusinessReference)
                .reconcileAttemptCount(0)
                .build();
        if (!operationTransactionService.tryClaim(operationRecord)) {
            return resolveExistingOperation(
                    operationId,
                    operationType,
                    userId,
                    requestFingerprint,
                    resultType);
        }

        operationLeaseService.activate(lease);
        try {
            // 只有成功认领唯一键的请求可以进入真实票务写链路。
            T result = Objects.requireNonNull(operation.get(), "业务操作结果不能为空");
            String resultReference = resultReference(result, initialBusinessReference);

            // 成功终态必须匹配当前实例和 epoch；租约失效的迟到响应不能覆盖恢复状态。
            operationTransactionService.markSucceeded(
                    operationId, lease.owner(), lease.epoch(),
                    JSON.toJSONString(result), resultReference);
            return result;
        } catch (RuntimeException exception) {
            markUnknown(lease, exception);
            throw exception;
        } finally {
            // 无论成功或异常都停止 JVM 内心跳，后续恢复完全由数据库状态驱动。
            operationLeaseService.deactivate(lease);
        }
    }

    /**
     * 查询当前用户稳定操作标识对应的下游持久化状态。
     *
     * @param operationId 调用方生成的操作标识
     * @return 不包含证件号和第三方交易凭证的操作状态
     */
    public BusinessOperationStatusRespDTO getStatus(String operationId) {
        String normalized = normalizeOperationId(operationId);
        if (normalized == null) {
            throw new ServiceException("业务操作标识不能为空");
        }

        // 状态查询仍按当前网关身份校验归属，禁止通过 actionId 探测其他用户订单。
        BusinessOperationDO operation = operationTransactionService.findById(normalized);
        if (operation == null || !requireCurrentUserId().equals(operation.getUserId())) {
            throw new ServiceException("业务操作不存在");
        }
        return BusinessOperationStatusRespDTO.builder()
                .operationId(operation.getOperationId())
                .operationType(operation.getOperationType())
                .status(statusName(operation.getStatus()))
                .safeResultJson(safeResultJson(operation))
                .failureMessage(operation.getFailureMessage())
                .build();
    }

    /**
     * 将数据库状态码转换为稳定协议值。
     *
     * @param status 数据库存储状态
     * @return 对账接口状态名称
     */
    private String statusName(Integer status) {
        if (Objects.equals(status, STATUS_PROCESSING)) {
            return "PROCESSING";
        }
        if (Objects.equals(status, STATUS_SUCCEEDED)) {
            return "SUCCEEDED";
        }
        if (Objects.equals(status, STATUS_FAILED)) {
            return "FAILED";
        }
        if (Objects.equals(status, STATUS_UNKNOWN)) {
            return "UNKNOWN";
        }
        if (Objects.equals(status, STATUS_RECONCILING)) {
            return "RECONCILING";
        }
        if (Objects.equals(status, STATUS_MANUAL_REVIEW)) {
            return "MANUAL_REVIEW";
        }
        throw new ServiceException("业务操作状态无效");
    }

    /**
     * 根据业务类型从原始成功响应中生成最小白名单结果。
     *
     * @param operation 已校验归属的操作记录
     * @return 成功操作的脱敏 JSON，非成功状态返回 null
     */
    private String safeResultJson(BusinessOperationDO operation) {
        if (!Objects.equals(operation.getStatus(), STATUS_SUCCEEDED)) {
            return null;
        }
        if (StrUtil.isBlank(operation.getResultJson())) {
            throw new ServiceException("业务操作成功结果缺失");
        }

        // 购票结果显式重建车票白名单，证件类型和证件号不会进入对账响应。
        return switch (operation.getOperationType()) {
            case PURCHASE_OPERATION_TYPE -> {
                TicketPurchaseRespDTO result = JSON.parseObject(
                        operation.getResultJson(), TicketPurchaseRespDTO.class);
                List<SafePurchasedTicket> tickets = result.getTicketOrderDetails() == null
                        ? List.of()
                        : result.getTicketOrderDetails().stream().map(this::safeTicket).toList();
                yield JSON.toJSONString(new SafePurchaseResult(result.getOrderSn(), tickets));
            }
            case CANCELLATION_OPERATION_TYPE -> JSON.toJSONString(
                    new SafeCancellationResult(Boolean.TRUE));
            case REFUND_OPERATION_TYPE -> {
                RefundTicketRespDTO result = JSON.parseObject(
                        operation.getResultJson(), RefundTicketRespDTO.class);
                yield JSON.toJSONString(new SafeRefundResult(
                        result.getRequestId(), result.getOrderSn(), result.getType(),
                        result.getRefundAmount(), result.getStatus()));
            }
            default -> throw new ServiceException("业务操作类型无效");
        };
    }

    /**
     * 将购票明细转换为不含证件字段的对账结果。
     *
     * @param detail 原始购票明细
     * @return 白名单车票明细
     */
    private SafePurchasedTicket safeTicket(TicketOrderDetailRespDTO detail) {
        return new SafePurchasedTicket(
                detail.getSeatType(), detail.getCarriageNumber(), detail.getSeatNumber(),
                detail.getRealName(), detail.getTicketType(), detail.getAmount());
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
        if (Objects.equals(existing.getStatus(), STATUS_UNKNOWN)
                || Objects.equals(existing.getStatus(), STATUS_RECONCILING)
                || Objects.equals(existing.getStatus(), STATUS_MANUAL_REVIEW)) {
            throw new ServiceException("业务操作结果正在核对，请勿使用新的操作标识重复提交");
        }
        throw new ServiceException("业务操作状态无效");
    }

    /**
     * 在真实写操作失败后持久化失败终态，同时保留原业务异常。
     *
     * @param operationId 操作标识
     * @param exception 原业务异常
     */
    private void markUnknown(OperationLease lease, RuntimeException exception) {
        try {
            // 异常发生点可能位于下游提交之后，保守进入 UNKNOWN 才能避免二次扣票或退款。
            operationTransactionService.markUnknown(
                    lease.operationId(), lease.owner(), lease.epoch(),
                    safeFailureMessage(exception), "DOWNSTREAM_RESULT_UNKNOWN");
        } catch (RuntimeException persistenceException) {
            log.error("业务操作未知状态持久化异常，operationId={}", lease.operationId(), persistenceException);
            exception.addSuppressed(persistenceException);
        }
    }

    /**
     * 从成功结果中提取恢复和审计所需的最小业务引用。
     *
     * @param result 已确认成功的业务结果
     * @param fallback 调用前已知的订单号
     * @return 订单号等安全引用
     */
    private String resultReference(Object result, String fallback) {
        if (result instanceof TicketPurchaseRespDTO purchaseResult) {
            return purchaseResult.getOrderSn();
        }
        if (result instanceof RefundTicketRespDTO refundResult) {
            return refundResult.getOrderSn();
        }
        return fallback;
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

    /**
     * 购票对账白名单结果。
     *
     * @param orderSn 订单号
     * @param tickets 不含证件信息的车票明细
     */
    private record SafePurchaseResult(String orderSn, List<SafePurchasedTicket> tickets) {
    }

    /**
     * 购票对账白名单车票明细。
     */
    private record SafePurchasedTicket(
            Integer seatType,
            String carriageNumber,
            String seatNumber,
            String realName,
            Integer ticketType,
            Integer amount) {
    }

    /**
     * 取消订单对账白名单结果，订单号由 Agent 不可变草案恢复。
     *
     * @param cancelled 是否已经成功取消
     */
    private record SafeCancellationResult(Boolean cancelled) {
    }

    /**
     * 退票对账白名单结果，不包含第三方退款交易凭证。
     */
    private record SafeRefundResult(
            String requestId,
            String orderSn,
            Integer type,
            Integer refundAmount,
            Integer status) {
    }
}
