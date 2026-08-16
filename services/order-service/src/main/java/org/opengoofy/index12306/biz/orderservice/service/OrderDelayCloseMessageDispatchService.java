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

package org.opengoofy.index12306.biz.orderservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.opengoofy.index12306.biz.orderservice.common.enums.DelayCloseMessageStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderCommandDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderCommandMapper;
import org.opengoofy.index12306.biz.orderservice.mq.event.DelayCloseOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.produce.DelayCloseOrderSendProduce;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 负责在订单事务提交后投递延迟关单消息，并从持久化命令表恢复失败任务。
 */
@Slf4j
@Service
public class OrderDelayCloseMessageDispatchService {

    private static final String ORDER_COMMAND_SUCCEEDED = "SUCCEEDED";
    private static final int MAX_FAILURE_REASON_LENGTH = 128;

    private final OrderCommandMapper orderCommandMapper;
    private final DelayCloseOrderSendProduce delayCloseOrderSendProduce;

    private final Executor delayCloseMessageExecutor;

    /**
     * 创建可靠消息投递服务并绑定专用有界线程池。
     *
     * @param orderCommandMapper 命令和 Outbox 持久层
     * @param delayCloseOrderSendProduce RocketMQ 延迟关单生产者
     * @param delayCloseMessageExecutor 专用消息投递执行器
     */
    public OrderDelayCloseMessageDispatchService(
            OrderCommandMapper orderCommandMapper,
            DelayCloseOrderSendProduce delayCloseOrderSendProduce,
            @Qualifier("delayCloseMessageExecutor") Executor delayCloseMessageExecutor) {
        // 显式限定执行器，避免与 Spring 默认任务执行器发生注入歧义。
        this.orderCommandMapper = orderCommandMapper;
        this.delayCloseOrderSendProduce = delayCloseOrderSendProduce;
        this.delayCloseMessageExecutor = delayCloseMessageExecutor;
    }

    @Value("${order.delay-close-message.recovery-batch-size:1000}")
    private int recoveryBatchSize;

    @Value("${order.delay-close-message.claim-lease-millis:15000}")
    private long claimLeaseMillis;

    @Value("${order.delay-close-message.retry-base-millis:1000}")
    private long retryBaseMillis;

    @Value("${order.delay-close-message.retry-max-millis:60000}")
    private long retryMaxMillis;

    /**
     * 将刚提交订单的消息投递放入专用线程池，调用线程不等待 RocketMQ 网络结果。
     *
     * @param userId 订单用户分片键
     * @param commandId 订单创建命令标识
     * @param orderSn 已提交订单号
     */
    public void dispatchAsync(String userId, String commandId, String orderSn) {
        if (commandId == null || commandId.isBlank()) {
            return;
        }
        try {
            // 即时任务只携带路由键和订单号，发送资格仍由数据库条件更新裁决。
            delayCloseMessageExecutor.execute(() -> dispatchOne(userId, commandId, orderSn, 0));
        } catch (RuntimeException exception) {
            // 队列满或执行器关闭时不影响已提交订单，恢复扫描会继续处理持久化待发送状态。
            log.warn("延迟关单即时投递进入线程池失败，等待恢复扫描，commandId={}, orderSn={}",
                    commandId, orderSn, exception);
        }
    }

    /**
     * 周期扫描未发送或租约过期的延迟关单消息，补偿进程退出、线程池拒绝和 MQ 发送失败。
     */
    @Scheduled(
            fixedDelayString = "${order.delay-close-message.recovery-interval-millis:60000}",
            initialDelayString = "${order.delay-close-message.recovery-initial-delay-millis:60000}")
    public void recoverPendingMessages() {
        Date now = new Date();
        // 恢复扫描是低频兜底；正常订单由提交后的即时任务投递，避免持续广播扫描干扰购票压测。
        List<OrderCommandDO> candidates = orderCommandMapper.selectList(
                Wrappers.lambdaQuery(OrderCommandDO.class)
                        .eq(OrderCommandDO::getStatus, ORDER_COMMAND_SUCCEEDED)
                        .in(OrderCommandDO::getDelayCloseStatus,
                                DelayCloseMessageStatusEnum.PENDING.getStatus(),
                                DelayCloseMessageStatusEnum.SENDING.getStatus())
                        .le(OrderCommandDO::getDelayCloseNextRetryTime, now)
                        .orderByAsc(OrderCommandDO::getDelayCloseNextRetryTime)
                        .last("LIMIT " + recoveryBatchSize));
        candidates.forEach(each -> {
            try {
                // 恢复任务也进入同一有界线程池，防止一次扫描占满调度线程。
                delayCloseMessageExecutor.execute(() -> dispatchOne(
                        each.getUserId(), each.getCommandId(), each.getOrderSn(),
                        defaultRetryCount(each.getDelayCloseRetryCount())));
            } catch (RuntimeException exception) {
                log.warn("延迟关单恢复任务进入线程池失败，commandId={}, orderSn={}",
                        each.getCommandId(), each.getOrderSn(), exception);
                return;
            }
        });
    }

    /**
     * 竞争消息租约后执行一次 RocketMQ 投递，并持久化成功或下一次重试时间。
     *
     * @param userId 订单用户分片键
     * @param commandId 订单创建命令标识
     * @param orderSn 待关闭订单号
     * @param retryCount 当前已失败次数
     */
    private void dispatchOne(String userId, String commandId, String orderSn, int retryCount) {
        Date claimDeadline = new Date(System.currentTimeMillis() + claimLeaseMillis);
        // 条件更新是多实例唯一认领点，同时允许发送中实例宕机后由过期租约重新接管。
        int claimed = orderCommandMapper.update(null,
                Wrappers.lambdaUpdate(OrderCommandDO.class)
                        .set(OrderCommandDO::getDelayCloseStatus, DelayCloseMessageStatusEnum.SENDING.getStatus())
                        .set(OrderCommandDO::getDelayCloseNextRetryTime, claimDeadline)
                        .eq(OrderCommandDO::getUserId, userId)
                        .eq(OrderCommandDO::getCommandId, commandId)
                        .eq(OrderCommandDO::getStatus, ORDER_COMMAND_SUCCEEDED)
                        .in(OrderCommandDO::getDelayCloseStatus,
                                DelayCloseMessageStatusEnum.PENDING.getStatus(),
                                DelayCloseMessageStatusEnum.SENDING.getStatus())
                        .le(OrderCommandDO::getDelayCloseNextRetryTime, new Date()));
        if (claimed != 1) {
            return;
        }
        try {
            // 数据库连接已经释放，RocketMQ 同步确认只占用后台消息线程。
            SendResult sendResult = delayCloseOrderSendProduce.sendMessage(
                    DelayCloseOrderEvent.builder().orderSn(orderSn).build());
            if (!SendStatus.SEND_OK.equals(sendResult.getSendStatus())) {
                throw new IllegalStateException("RocketMQ send status is " + sendResult.getSendStatus());
            }
            markSent(userId, commandId);
        } catch (Throwable throwable) {
            markRetry(userId, commandId, retryCount + 1, throwable);
        }
    }

    /**
     * 将 RocketMQ 已确认的记录推进到已发送终态。
     *
     * @param userId 订单用户分片键
     * @param commandId 订单创建命令标识
     */
    private void markSent(String userId, String commandId) {
        // 只允许当前发送中租约变为已发送，重复回调不会反向覆盖终态。
        orderCommandMapper.update(null,
                Wrappers.lambdaUpdate(OrderCommandDO.class)
                        .set(OrderCommandDO::getDelayCloseStatus, DelayCloseMessageStatusEnum.SENT.getStatus())
                        .set(OrderCommandDO::getDelayCloseFailureReason, null)
                        .eq(OrderCommandDO::getUserId, userId)
                        .eq(OrderCommandDO::getCommandId, commandId)
                        .eq(OrderCommandDO::getDelayCloseStatus, DelayCloseMessageStatusEnum.SENDING.getStatus()));
    }

    /**
     * 记录本次投递失败并计算下一次重试时间，不把基础设施失败传播到购票请求。
     *
     * @param userId 订单用户分片键
     * @param commandId 订单创建命令标识
     * @param retryCount 更新后的失败次数
     * @param throwable 本次发送异常
     */
    private void markRetry(String userId, String commandId, int retryCount, Throwable throwable) {
        long retryDelay = Math.min(retryMaxMillis,
                retryBaseMillis * (1L << Math.min(Math.max(retryCount - 1, 0), 10)));
        Date nextRetryTime = new Date(System.currentTimeMillis() + retryDelay);
        String failureReason = abbreviate(throwable.getClass().getSimpleName(), MAX_FAILURE_REASON_LENGTH);
        // 失败记录回到待发送，下一轮恢复扫描按退避时间重新竞争。
        orderCommandMapper.update(null,
                Wrappers.lambdaUpdate(OrderCommandDO.class)
                        .set(OrderCommandDO::getDelayCloseStatus, DelayCloseMessageStatusEnum.PENDING.getStatus())
                        .set(OrderCommandDO::getDelayCloseRetryCount, retryCount)
                        .set(OrderCommandDO::getDelayCloseNextRetryTime, nextRetryTime)
                        .set(OrderCommandDO::getDelayCloseFailureReason, failureReason)
                        .eq(OrderCommandDO::getUserId, userId)
                        .eq(OrderCommandDO::getCommandId, commandId)
                        .eq(OrderCommandDO::getDelayCloseStatus, DelayCloseMessageStatusEnum.SENDING.getStatus()));
        log.warn("延迟关单消息发送失败，等待重试，commandId={}, retryCount={}",
                commandId, retryCount, throwable);
    }

    /**
     * 将可空重试次数转换为数值，兼容升级前或手工初始化的数据。
     *
     * @param retryCount 数据库存储的重试次数
     * @return 非负重试次数
     */
    private int defaultRetryCount(Integer retryCount) {
        // 空值只可能来自旧记录，按尚未失败处理。
        return retryCount == null ? 0 : Math.max(retryCount, 0);
    }

    /**
     * 截断数据库可安全保存的失败摘要。
     *
     * @param value 原始失败类型
     * @param maxLength 最大字符数
     * @return 截断后的非空字符串
     */
    private String abbreviate(String value, int maxLength) {
        // 只保存异常类型，不写入可能包含敏感参数的异常正文。
        String normalized = value == null || value.isBlank() ? "UnknownFailure" : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
