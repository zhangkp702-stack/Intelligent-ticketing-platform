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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.RefundTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.OrderCommandStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundCommandStatusRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationCoordinator;
import org.opengoofy.index12306.biz.ticketservice.service.BusinessOperationRecoveryService;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandService;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用订单和支付服务的稳定 commandId 查询收敛 ticket-service 未知状态。
 */
@Slf4j
@Service
public class BusinessOperationRecoveryServiceImpl implements BusinessOperationRecoveryService {

    private static final String PURCHASE_OPERATION_TYPE = "PURCHASE_TICKET";
    private static final String CANCELLATION_OPERATION_TYPE = "CANCEL_TICKET_ORDER";
    private static final String REFUND_OPERATION_TYPE = "REFUND_TICKET";
    private static final int ORDER_STATUS_CLOSED = 30;
    private static final int RECOVERY_BATCH_SIZE = 100;

    private final ReliableCommandService reliableCommandService;
    private final BusinessOperationCoordinator operationCoordinator;
    private final TicketOrderRemoteService orderRemoteService;
    private final PayRemoteService payRemoteService;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final long reconciliationLeaseMillis;
    private final AtomicBoolean scanning = new AtomicBoolean();

    /**
     * 创建跨服务业务操作恢复器。
     *
     * @param reliableCommandService 通用可靠命令服务
     * @param operationCoordinator 操作状态归属查询服务
     * @param orderRemoteService 订单权威查询客户端
     * @param payRemoteService 支付退款权威查询客户端
     * @param maxAttempts 最大自动对账次数
     * @param retryDelayMillis 对账重试间隔
     * @param reconciliationLeaseMillis 单次只读对账租约时长
     */
    public BusinessOperationRecoveryServiceImpl(
            ReliableCommandService reliableCommandService,
            BusinessOperationCoordinator operationCoordinator,
            TicketOrderRemoteService orderRemoteService,
            PayRemoteService payRemoteService,
            @Value("${index12306.ticket.operation.reconcile-max-attempts:5}") int maxAttempts,
            @Value("${index12306.ticket.operation.reconcile-retry-delay-millis:30000}")
            long retryDelayMillis,
            @Value("${index12306.ticket.operation.reconcile-lease-millis:120000}")
            long reconciliationLeaseMillis) {
        this.reliableCommandService = reliableCommandService;
        this.operationCoordinator = operationCoordinator;
        this.orderRemoteService = orderRemoteService;
        this.payRemoteService = payRemoteService;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMillis = Math.max(1000L, retryDelayMillis);
        this.reconciliationLeaseMillis = Math.max(30000L, reconciliationLeaseMillis);
    }

    /**
     * 周期扫描租约过期和待对账操作，单实例内避免重叠扫描。
     */
    @Scheduled(fixedDelayString = "${index12306.ticket.operation.recovery-interval-millis:5000}")
    public void scheduledRecovery() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            recoverDueOperations();
        } finally {
            scanning.set(false);
        }
    }

    /**
     * 将过期 PROCESSING 转 UNKNOWN，并对到期记录执行权威只读查询。
     *
     * @return 本轮成功收敛数量
     */
    @Override
    public int recoverDueOperations() {
        // 第一步只迁移状态，绝不在租约过期后重新调用购票、取消或退款接口。
        reliableCommandService.recoverExpiredLeases(
                BusinessOperationCoordinator.COMMAND_NAMESPACE, RECOVERY_BATCH_SIZE);
        int terminalCount = 0;
        for (ReliableCommandRecord candidate : reliableCommandService.findDueReconciliations(
                BusinessOperationCoordinator.COMMAND_NAMESPACE, RECOVERY_BATCH_SIZE)) {
            terminalCount += reconcile(candidate) ? 1 : 0;
        }
        return terminalCount;
    }

    /**
     * 校验当前用户归属后触发指定操作的只读对账。
     *
     * @param operationId 业务操作标识
     * @return 对账后最新状态
     */
    @Override
    public String reconcileNow(String operationId) {
        // 先复用公开状态查询完成用户归属校验，防止通过恢复接口探测他人操作。
        operationCoordinator.getStatus(operationId);
        ReliableCommandRecord operation = reliableCommandService.find(
                BusinessOperationCoordinator.commandKey(operationId)).orElse(null);
        if (operation != null) {
            reconcile(operation);
        }
        return operationCoordinator.getStatus(operationId).getStatus();
    }

    /**
     * 唯一领取一条 UNKNOWN 记录并按操作类型查询下游事实。
     *
     * @param candidate 待领取的 UNKNOWN 命令
     * @return 已收敛为成功终态返回 true
     */
    private boolean reconcile(ReliableCommandRecord candidate) {
        ReliableCommandRecord operation = reliableCommandService.claimReconciliation(
                candidate.key(), Duration.ofMillis(reconciliationLeaseMillis)).orElse(null);
        if (operation == null) {
            return false;
        }

        UserInfoDTO previousUser = currentUser();
        try {
            // 后台线程恢复原用户身份，仅用于下游所有权校验和 Feign 头传递。
            UserContext.setUser(UserInfoDTO.builder().userId(operation.ownerId()).build());
            RecoveryResult result = queryDownstream(operation);
            if (result.succeeded()) {
                return reliableCommandService.reconcileSucceeded(
                        operation, result.resultJson(), result.businessReference(), result.evidence());
            }
            scheduleNext(operation, result.evidence());
            return false;
        } catch (RuntimeException exception) {
            // 查询失败不改变真实业务事实，返回 UNKNOWN 后按退避时间继续只读查询。
            scheduleNext(operation, "QUERY_ERROR:" + exception.getClass().getSimpleName());
            log.warn("业务操作只读对账失败，operationId={}, operationType={}",
                    operation.key().commandId(), operation.commandType(), exception);
            return false;
        } finally {
            // HTTP 手动触发场景需要恢复原线程身份；后台任务原本没有身份则直接清理。
            if (previousUser == null) {
                UserContext.removeUser();
            } else {
                UserContext.setUser(previousUser);
            }
        }
    }

    /**
     * 复制当前线程用户身份，供只读对账临时切换后恢复。
     *
     * @return 当前没有用户上下文时返回 null
     */
    private UserInfoDTO currentUser() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            return null;
        }
        // 只复制既有线程字段，不扩充或伪造权限信息。
        return UserInfoDTO.builder()
                .userId(userId)
                .username(UserContext.getUsername())
                .realName(UserContext.getRealName())
                .token(UserContext.getToken())
                .build();
    }

    /**
     * 按操作类型查询订单或退款命令状态。
     *
     * @param operation 已领取的对账记录
     * @return 不包含第三方交易凭证的恢复结果
     */
    private RecoveryResult queryDownstream(ReliableCommandRecord operation) {
        return switch (operation.commandType()) {
            case PURCHASE_OPERATION_TYPE -> queryPurchase(operation);
            case CANCELLATION_OPERATION_TYPE -> queryCancellation(operation);
            case REFUND_OPERATION_TYPE -> queryRefund(operation);
            default -> throw new IllegalStateException("不支持的业务操作类型");
        };
    }

    /**
     * 通过 actionId 派生的建单 commandId 查询订单事实。
     *
     * @param operation 购票操作记录
     * @return 购票恢复结果
     */
    private RecoveryResult queryPurchase(ReliableCommandRecord operation) {
        String commandId = operation.key().commandId() + ":create-order";
        Result<OrderCommandStatusRespDTO> response = orderRemoteService.queryCommandStatus(commandId);
        OrderCommandStatusRespDTO status = successfulData(response);
        if (!"SUCCEEDED".equals(status.getStatus())) {
            return RecoveryResult.pending("ORDER_COMMAND:" + status.getStatus());
        }

        // Agent 对账协议要求 tickets 数组；命令恢复只返回订单号，因此使用空白名单数组。
        TicketPurchaseRespDTO result = TicketPurchaseRespDTO.builder()
                .orderSn(status.getOrderSn())
                .ticketOrderDetails(List.of())
                .build();
        return RecoveryResult.succeeded(
                JSON.toJSONString(result), status.getOrderSn(), "ORDER_COMMAND:SUCCEEDED");
    }

    /**
     * 查询订单当前状态，只有明确 CLOSED 才证明取消成功。
     *
     * @param operation 取消操作记录
     * @return 取消恢复结果
     */
    private RecoveryResult queryCancellation(ReliableCommandRecord operation) {
        if (operation.businessReference() == null) {
            return RecoveryResult.pending("ORDER_REFERENCE_MISSING");
        }
        Result<TicketOrderDetailRespDTO> response = orderRemoteService
                .querySelfTicketOrderByOrderSn(operation.businessReference());
        TicketOrderDetailRespDTO order = successfulData(response);
        if (!Integer.valueOf(ORDER_STATUS_CLOSED).equals(order.getStatus())) {
            return RecoveryResult.pending("ORDER_STATUS:" + order.getStatus());
        }
        return RecoveryResult.succeeded(
                JSON.toJSONString(Boolean.TRUE), operation.businessReference(), "ORDER_STATUS:CLOSED");
    }

    /**
     * 通过 actionId 派生的退款 commandId 查询支付退款事实。
     *
     * @param operation 退票操作记录
     * @return 退票恢复结果
     */
    private RecoveryResult queryRefund(ReliableCommandRecord operation) {
        String commandId = operation.key().commandId() + ":refund-payment";
        Result<RefundCommandStatusRespDTO> response = payRemoteService.queryRefundCommandStatus(commandId);
        RefundCommandStatusRespDTO status = successfulData(response);
        if (!"SUCCEEDED".equals(status.getStatus())) {
            return RecoveryResult.pending("REFUND_COMMAND:" + status.getStatus());
        }

        // 只重建 Agent 对账需要的白名单字段，tradeNo 不进入票务恢复表和审计日志。
        RefundTicketRespDTO result = new RefundTicketRespDTO();
        result.setRequestId(operation.key().commandId());
        result.setOrderSn(status.getOrderSn());
        result.setRefundAmount(status.getRefundAmount());
        result.setStatus(1);
        return RecoveryResult.succeeded(
                JSON.toJSONString(result), status.getOrderSn(), "REFUND_COMMAND:SUCCEEDED");
    }

    /**
     * 校验 Feign 统一结果并返回响应数据。
     *
     * @param response 下游统一响应
     * @param <T> 响应数据类型
     * @return 非空成功数据
     */
    private <T> T successfulData(Result<T> response) {
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new IllegalStateException("下游权威查询暂不可用");
        }
        return response.getData();
    }

    /**
     * 把未确定结果安排为下一次查询，达到上限时自动转人工处理。
     *
     * @param operation 当前对账记录
     * @param evidence 安全证据摘要
     */
    private void scheduleNext(ReliableCommandRecord operation, String evidence) {
        boolean exhausted = operation.reconcileAttemptCount() >= maxAttempts;
        ReliableCommandStatus targetStatus = exhausted
                ? ReliableCommandStatus.MANUAL_REVIEW
                : ReliableCommandStatus.UNKNOWN;
        Instant nextReconcileAt = exhausted ? null : Instant.now().plusMillis(retryDelayMillis);
        // 达到上限后停止自动查询并保留人工证据，否则按固定退避回到 UNKNOWN。
        reliableCommandService.finishReconciliation(
                operation,
                targetStatus,
                exhausted ? "RECONCILIATION_EXHAUSTED" : "RECONCILIATION_PENDING",
                evidence,
                evidence,
                nextReconcileAt);
    }

    /**
     * 一次权威只读查询的最小结果。
     *
     * @param succeeded 是否已确认成功
     * @param resultJson 可供原接口重放的安全结果
     * @param businessReference 订单号等安全引用
     * @param evidence 不包含敏感字段的证据摘要
     */
    private record RecoveryResult(
            boolean succeeded,
            String resultJson,
            String businessReference,
            String evidence) {

        /**
         * 创建已确认成功的恢复结果。
         *
         * @param resultJson 安全结果 JSON
         * @param businessReference 业务引用
         * @param evidence 证据摘要
         * @return 成功结果
         */
        private static RecoveryResult succeeded(
                String resultJson,
                String businessReference,
                String evidence) {
            return new RecoveryResult(true, resultJson, businessReference, evidence);
        }

        /**
         * 创建仍需继续查询的恢复结果。
         *
         * @param evidence 当前证据摘要
         * @return 待定结果
         */
        private static RecoveryResult pending(String evidence) {
            return new RecoveryResult(false, null, null, evidence);
        }
    }
}
