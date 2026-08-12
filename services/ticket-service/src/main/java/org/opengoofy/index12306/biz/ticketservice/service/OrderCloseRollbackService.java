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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandClaim;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandDefinition;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandKey;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandMode;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandRecord;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandService;
import org.opengoofy.index12306.framework.starter.reliablecommand.core.ReliableCommandStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 协调订单关闭后的座位和余票回滚，使用持久化命令记录抵抗 Canal 与同步取消的重复触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCloseRollbackService {

    private static final String COMMAND_NAMESPACE = "ticket-order-close-rollback";
    private static final String COMMAND_TYPE = "ORDER_CLOSE_ROLLBACK";
    private static final String CONSUMER_OWNER = "ticket-order-close";
    private static final String FINGERPRINT_VERSION = "ticket-close-v1";
    private static final int RECOVERY_BATCH_SIZE = 100;

    private final TicketSeatReservationReleaseService ticketSeatReservationReleaseService;
    private final ReliableCommandService reliableCommandService;

    /**
     * 认领指定订单的一次关闭回滚；重复的 Canal 事件或同步取消请求不会再次修改余票。
     *
     * @param orderSn 已关闭订单号
     */
    public void rollback(String orderSn) {
        // 订单号是关闭回滚的稳定业务键，持久化唯一键决定唯一执行者。
        ReliableCommandClaim claim = reliableCommandService.claim(new ReliableCommandDefinition(
                commandKey(orderSn), COMMAND_TYPE, ReliableCommandMode.REMOTE_EFFECT,
                CONSUMER_OWNER, orderSn, FINGERPRINT_VERSION, orderSn));
        if (!claim.acquired()) {
            return;
        }

        ReliableCommandRecord record = claim.record();
        try {
            // 只有成功认领的线程调用订单服务并执行真实座位、缓存回滚。
            rollbackTicketInventory(orderSn);
            if (!reliableCommandService.markSucceeded(record, Boolean.TRUE.toString(), orderSn)) {
                throw new ServiceException("订单关闭回滚执行权已失效");
            }
        } catch (RuntimeException exception) {
            // 下游或缓存调用中断时保留 UNKNOWN，由恢复器安全重放幂等回滚步骤。
            reliableCommandService.markUnknown(record, "ROLLBACK_RESULT_UNKNOWN",
                    safeFailureMessage(exception), Instant.now());
            throw exception;
        } finally {
            reliableCommandService.release(record);
        }
    }

    /**
     * 周期恢复租约过期或异常中断的订单关闭回滚记录。
     */
    @Scheduled(fixedDelayString = "${index12306.ticket.order-close-rollback.recovery-interval-millis:5000}")
    public void recoverDueRollbacks() {
        // 先把失去执行实例的 PROCESSING 记录转为 UNKNOWN，避免永久占用同一订单。
        reliableCommandService.recoverExpiredLeases(COMMAND_NAMESPACE, RECOVERY_BATCH_SIZE);
        for (ReliableCommandRecord candidate : reliableCommandService.findDueReconciliations(
                COMMAND_NAMESPACE, RECOVERY_BATCH_SIZE)) {
            recoverRollback(candidate);
        }
    }

    /**
     * 领取一条未知结果记录并安全重放；每个 reservation 分别记录座位、Redis 和令牌桶进度。
     *
     * @param candidate 待恢复的关闭回滚记录
     */
    private void recoverRollback(ReliableCommandRecord candidate) {
        ReliableCommandRecord record = reliableCommandService.claimReconciliation(
                candidate.key(), Duration.ofMinutes(2)).orElse(null);
        if (record == null || record.businessReference() == null) {
            return;
        }
        try {
            // 重新读取订单权威明细，再执行可重复的座位和缓存回滚。
            rollbackTicketInventory(record.businessReference());
            reliableCommandService.reconcileSucceeded(record, Boolean.TRUE.toString(),
                    record.businessReference(), "ROLLBACK_RETRIED");
        } catch (RuntimeException exception) {
            // 恢复失败不推断业务事实，继续保留 UNKNOWN 等待下一轮受控重试。
            reliableCommandService.finishReconciliation(record, ReliableCommandStatus.UNKNOWN,
                    "ROLLBACK_RETRY_ERROR", safeFailureMessage(exception),
                    "ROLLBACK_RETRY_ERROR", Instant.now().plusSeconds(30));
            log.warn("订单关闭回滚恢复失败，orderSn={}", record.businessReference(), exception);
        }
    }

    /**
     * 按 reservation 状态机推进实际座位和缓存回滚。
     *
     * @param orderSn 已关闭订单号
     */
    private void rollbackTicketInventory(String orderSn) {
        // Canal 与同步取消只提供订单号；具体可释放座位必须由本地 reservation 权威记录决定。
        ticketSeatReservationReleaseService.releaseOrder(orderSn);
    }

    /**
     * 构造订单关闭回滚的稳定可靠命令键。
     *
     * @param orderSn 订单号
     * @return 以订单号路由的可靠命令键
     */
    private ReliableCommandKey commandKey(String orderSn) {
        return new ReliableCommandKey(COMMAND_NAMESPACE, orderSn, orderSn);
    }

    /**
     * 提取可持久化的异常摘要，避免把堆栈或超长下游报文写入可靠命令表。
     *
     * @param exception 原始异常
     * @return 长度受限的异常摘要
     */
    private String safeFailureMessage(RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
