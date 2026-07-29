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
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_FAILED;
import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_PROCESSING;
import static org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationTransactionService.STATUS_SUCCEEDED;

/**
 * 为携带 Agent 操作标识的 V2 购票请求提供数据库级幂等保护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOperationService {

    private static final int OPERATION_ID_MAX_LENGTH = 64;
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 500;
    private static final String PURCHASE_OPERATION_TYPE = "PURCHASE_TICKET";

    private final TicketService ticketService;
    private final BusinessOperationTransactionService operationTransactionService;

    /**
     * 执行 V2 购票；普通请求直接执行，Agent 请求先持久化认领操作标识。
     *
     * @param requestParam 购票参数和可选操作标识
     * @return 新建订单，或者重复成功操作已经保存的原订单结果
     */
    public TicketPurchaseRespDTO purchaseTicketsV2(PurchaseTicketReqDTO requestParam) {
        if (requestParam == null) {
            throw new ServiceException("购票请求不能为空");
        }
        String operationId = normalizeOperationId(requestParam.getOperationId());
        if (operationId == null) {
            // 浏览器等普通调用方未提供操作标识时保持原有 V2 接口语义。
            return ticketService.purchaseTicketsV2(requestParam);
        }

        // Agent 操作同时绑定用户和业务参数摘要，禁止复用标识提交另一份购票请求。
        String userId = requireCurrentUserId();
        String requestFingerprint = fingerprint(requestParam);
        BusinessOperationDO operation = BusinessOperationDO.builder()
                .operationId(operationId)
                .operationType(PURCHASE_OPERATION_TYPE)
                .userId(userId)
                .requestFingerprint(requestFingerprint)
                .status(STATUS_PROCESSING)
                .build();
        if (!operationTransactionService.tryClaim(operation)) {
            return resolveExistingOperation(operationId, userId, requestFingerprint);
        }

        TicketPurchaseRespDTO result;
        try {
            // 只有成功认领主键的请求可以进入扣减令牌、选座和订单创建流程。
            result = ticketService.purchaseTicketsV2(requestParam);
        } catch (RuntimeException exception) {
            markFailed(operationId, exception);
            throw exception;
        }

        // 成功结果在独立事务中固化；若此处失败则保留 PROCESSING，禁止不确定请求自动重试。
        operationTransactionService.markSucceeded(operationId, JSON.toJSONString(result));
        return result;
    }

    /**
     * 解析已经存在的操作状态，并在成功时返回保存的原始结果。
     *
     * @param operationId 操作标识
     * @param userId 当前用户标识
     * @param requestFingerprint 当前业务参数摘要
     * @return 已成功操作保存的购票结果
     */
    private TicketPurchaseRespDTO resolveExistingOperation(
            String operationId,
            String userId,
            String requestFingerprint) {
        BusinessOperationDO existing = operationTransactionService.findById(operationId);
        if (existing == null) {
            throw new ServiceException("购票操作状态暂不可用，请稍后重试");
        }

        // 操作标识必须始终属于同一用户、同一业务类型和同一份不可变购票参数。
        if (!PURCHASE_OPERATION_TYPE.equals(existing.getOperationType())
                || !userId.equals(existing.getUserId())
                || !requestFingerprint.equals(existing.getRequestFingerprint())) {
            throw new ServiceException("购票操作标识与原请求不一致");
        }
        if (Objects.equals(existing.getStatus(), STATUS_SUCCEEDED)) {
            return JSON.parseObject(existing.getResultJson(), TicketPurchaseRespDTO.class);
        }
        if (Objects.equals(existing.getStatus(), STATUS_PROCESSING)) {
            throw new ServiceException("购票请求正在处理中，请稍后查询订单");
        }
        if (Objects.equals(existing.getStatus(), STATUS_FAILED)) {
            throw new ServiceException("该购票请求此前执行失败，请使用新的操作标识重试");
        }
        throw new ServiceException("购票操作状态无效");
    }

    /**
     * 在购票失败后持久化失败终态，同时保留原业务异常。
     *
     * @param operationId 操作标识
     * @param exception 原业务异常
     */
    private void markFailed(String operationId, RuntimeException exception) {
        try {
            // 失败摘要只用于运维定位，不持久化完整异常堆栈或请求敏感字段。
            operationTransactionService.markFailed(operationId, safeFailureMessage(exception));
        } catch (RuntimeException persistenceException) {
            log.error("购票操作失败状态持久化异常，operationId={}", operationId, persistenceException);
            exception.addSuppressed(persistenceException);
        }
    }

    /**
     * 清理并校验调用方提供的操作标识。
     *
     * @param operationId 原始操作标识
     * @return 规范化操作标识，未提供时返回 null
     */
    private String normalizeOperationId(String operationId) {
        if (StrUtil.isBlank(operationId)) {
            return null;
        }
        String normalized = operationId.trim();
        if (normalized.length() > OPERATION_ID_MAX_LENGTH) {
            throw new ServiceException("购票操作标识长度不能超过 64 个字符");
        }
        return normalized;
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
     * 计算不包含 operationId 的稳定业务参数摘要。
     *
     * @param requestParam 购票参数
     * @return SHA-256 十六进制摘要
     */
    private String fingerprint(PurchaseTicketReqDTO requestParam) {
        PurchaseFingerprintPayload payload = new PurchaseFingerprintPayload(
                requestParam.getTrainId(),
                requestParam.getDepartureDate(),
                requestParam.getPassengers(),
                requestParam.getChooseSeats(),
                requestParam.getDeparture(),
                requestParam.getArrival());
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
     * @param exception 购票异常
     * @return 可持久化失败摘要
     */
    private String safeFailureMessage(RuntimeException exception) {
        String message = StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getSimpleName());
        return message.length() <= FAILURE_MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, FAILURE_MESSAGE_MAX_LENGTH);
    }

    /**
     * 用于生成购票业务参数摘要的不可变数据。
     *
     * @param trainId 车次标识
     * @param departureDate 乘车日期
     * @param passengers 乘车人与席别
     * @param chooseSeats 选座偏好
     * @param departure 出发站
     * @param arrival 到达站
     */
    private record PurchaseFingerprintPayload(
            String trainId,
            Date departureDate,
            List<PurchaseTicketPassengerDetailDTO> passengers,
            List<String> chooseSeats,
            String departure,
            String arrival) {
    }
}
