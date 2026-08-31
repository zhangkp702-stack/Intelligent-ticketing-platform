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

package org.opengoofy.index12306.biz.canalrelay.relay;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.opengoofy.index12306.biz.canalrelay.config.CanalRelayProperties;
import org.opengoofy.index12306.biz.canalrelay.relay.CanalOutboxMessageRouter.RelayCommand;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立执行 Canal 到 RocketMQ 的可靠转发。
 *
 * <p>每个 Binlog 批次只有所有目标消息都被 RocketMQ 确认后才 ACK；发送失败、解析失败或本地背压都会回滚批次。</p>
 */
@Slf4j
@Component
public class CanalOutboxRelay implements SmartLifecycle {

    private final CanalRelayProperties properties;
    private final CanalConnectorFactory connectorFactory;
    private final CanalOutboxMessageRouter messageRouter;
    private final RocketMQTemplate rocketMQTemplate;
    private final Executor publisherExecutor;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile CanalConnector currentConnector;
    private volatile Thread workerThread;

    /**
     * 创建独立 Relay，并注入 Canal 客户端、路由器和有界发送器。
     *
     * @param properties Canal 连接和背压配置
     * @param connectorFactory Canal 客户端工厂
     * @param messageRouter Outbox INSERT 路由器
     * @param rocketMQTemplate RocketMQ 命令发送器
     * @param publisherExecutor 有界 Broker 发送执行器
     */
    public CanalOutboxRelay(
            CanalRelayProperties properties,
            CanalConnectorFactory connectorFactory,
            CanalOutboxMessageRouter messageRouter,
            RocketMQTemplate rocketMQTemplate,
            @Qualifier("canalRelayPublisherExecutor") Executor publisherExecutor) {
        this.properties = properties;
        this.connectorFactory = connectorFactory;
        this.messageRouter = messageRouter;
        this.rocketMQTemplate = rocketMQTemplate;
        this.publisherExecutor = publisherExecutor;
    }

    /**
     * 启动单个 Canal 消费循环，保证同一 destination 的 ACK 顺序不被并发打乱。
     */
    @Override
    public void start() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        // 单线程串行维护 Canal 位点；批次内发送由独立有界线程池并行完成。
        workerThread = new Thread(this::runLoop, "canal-outbox-relay");
        workerThread.setDaemon(false);
        workerThread.start();
    }

    /**
     * 停止读取新批次并断开当前 Canal 连接，使阻塞读取尽快返回。
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // 先断开 TCP 连接解除 getWithoutAck 阻塞，再等待工作线程收尾当前批次。
        disconnectQuietly(currentConnector);
        Thread currentWorker = workerThread;
        if (currentWorker != null && currentWorker != Thread.currentThread()) {
            try {
                currentWorker.join(2000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 返回 Relay 是否正在运行。
     *
     * @return 工作线程已启动且尚未请求停止时返回 true
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 让 Relay 随 Spring 容器自动启动。
     *
     * @return 始终返回 true
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * Relay 仅依赖 RocketMQ 自动配置，使用默认生命周期阶段启动。
     *
     * @return 生命周期阶段值
     */
    @Override
    public int getPhase() {
        return 0;
    }

    /**
     * 提供 Spring 生命周期回调形式的停止入口。
     *
     * @param callback Spring 在停止完成后执行的回调
     */
    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    /**
     * 建立 Canal 连接并持续读取批次；连接异常采用有上限退避重试。
     */
    private void runLoop() {
        long retryDelayMillis = normalizedReconnectInitialBackoff();
        while (running.get()) {
            CanalConnector connector = null;
            try {
                connector = connectorFactory.create(properties);
                currentConnector = connector;
                // 连接和订阅成功后才重置退避，避免不可用 Canal 被高频重连压垮。
                connector.connect();
                connector.subscribe(properties.getSubscription());
                retryDelayMillis = normalizedReconnectInitialBackoff();
                log.info("canal_relay_connected destination={}, subscription={}",
                        properties.getDestination(), properties.getSubscription());
                consumeConnected(connector);
            } catch (RuntimeException exception) {
                if (running.get()) {
                    log.error("canal_relay_connection_failed destination={}, retryDelayMillis={}, exceptionType={}",
                            properties.getDestination(), retryDelayMillis, exception.getClass().getSimpleName(), exception);
                    sleepQuietly(retryDelayMillis);
                    retryDelayMillis = nextReconnectBackoff(retryDelayMillis);
                }
            } finally {
                if (currentConnector == connector) {
                    currentConnector = null;
                }
                disconnectQuietly(connector);
            }
        }
    }

    /**
     * 在连接有效时读取 Canal 批次，并在每个批次完成后决定 ACK 或回滚。
     *
     * @param connector 已连接且已订阅的 Canal 客户端
     */
    private void consumeConnected(CanalConnector connector) {
        while (running.get()) {
            Message message = connector.getWithoutAck(Math.max(1, properties.getBatchSize()));
            if (message == null || message.getId() < 0 || message.getEntries().isEmpty()) {
                sleepQuietly(Math.max(1L, properties.getIdleBackoffMillis()));
                continue;
            }
            processBatch(connector, message);
        }
    }

    /**
     * 投递单个 Canal 批次，所有目标消息确认后才推进该批次位点。
     *
     * @param connector 当前 Canal 客户端
     * @param message 已领取、尚未 ACK 的 Canal 批次
     */
    void processBatch(CanalConnector connector, Message message) {
        long batchId = message.getId();
        try {
            List<RelayCommand> commands = messageRouter.route(message.getEntries());
            // 同一批次并行发送，但在全部 Future 成功前不会 ACK Binlog 位点。
            publishAll(commands);
            connector.ack(batchId);
            if (!commands.isEmpty()) {
                log.info("canal_relay_batch_acked batchId={}, commandCount={}", batchId, commands.size());
            }
        } catch (RuntimeException exception) {
            // ACK 前的任何不确定结果都必须回滚整批，依靠 commandId 吸收重放产生的重复消息。
            rollbackQuietly(connector, batchId);
            throw exception;
        }
    }

    /**
     * 通过有界线程池并行发送一批已经校验的订单创建命令。
     *
     * @param commands 当前 Canal 批次中的目标命令
     */
    private void publishAll(List<RelayCommand> commands) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(commands.size());
        for (RelayCommand command : commands) {
            futures.add(CompletableFuture.runAsync(() -> publishOne(command), publisherExecutor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException exception) {
            throw new IllegalStateException("RocketMQ 未确认完整 Canal 批次", exception.getCause());
        }
    }

    /**
     * 发送一条稳定订单创建命令，并验证 RocketMQ 已返回消息标识。
     *
     * @param command 已通过 Outbox 主键一致性校验的命令
     */
    private void publishOne(RelayCommand command) {
        SendResult sendResult = rocketMQTemplate.syncSend(
                command.destinationTopic() + ":" + command.destinationTag(), command.payload());
        if (sendResult == null || sendResult.getMsgId() == null || sendResult.getMsgId().isBlank()) {
            throw new IllegalStateException("RocketMQ 未确认订单创建命令");
        }
        // 日志只保留稳定业务标识，不输出身份证号、手机号或完整订单载荷。
        log.info("canal_relay_command_published eventId={}, aggregateId={}, commandId={}",
                command.eventId(), command.aggregateId(), command.commandId());
    }

    /**
     * 计算受限指数退避，避免 Canal 短暂故障时产生连接风暴。
     *
     * @param currentDelayMillis 当前等待时间
     * @return 下一次连接失败时的等待时间
     */
    private long nextReconnectBackoff(long currentDelayMillis) {
        long maximum = Math.max(normalizedReconnectInitialBackoff(), properties.getReconnectMaxBackoffMillis());
        return Math.min(maximum, Math.max(1L, currentDelayMillis) * 2L);
    }

    /**
     * 获取至少为一毫秒的首次重连等待时间。
     *
     * @return 规范化后的首次退避时间
     */
    private long normalizedReconnectInitialBackoff() {
        return Math.max(1L, properties.getReconnectInitialBackoffMillis());
    }

    /**
     * 在不中断正常关闭语义的前提下等待指定时间。
     *
     * @param millis 等待毫秒数
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    /**
     * 回滚未确认批次；回滚异常不覆盖原始发送或解析异常。
     *
     * @param connector 当前 Canal 客户端
     * @param batchId 待回滚的 Canal 批次标识
     */
    private void rollbackQuietly(CanalConnector connector, long batchId) {
        try {
            connector.rollback(batchId);
        } catch (RuntimeException rollbackException) {
            log.error("canal_relay_batch_rollback_failed batchId={}, exceptionType={}",
                    batchId, rollbackException.getClass().getSimpleName(), rollbackException);
        }
    }

    /**
     * 断开 Canal 连接；关闭期间的网络异常不阻止进程退出。
     *
     * @param connector 待关闭的 Canal 客户端
     */
    private void disconnectQuietly(CanalConnector connector) {
        if (connector == null) {
            return;
        }
        try {
            connector.disconnect();
        } catch (RuntimeException exception) {
            log.debug("canal_relay_disconnect_failed exceptionType={}", exception.getClass().getSimpleName());
        }
    }
}
