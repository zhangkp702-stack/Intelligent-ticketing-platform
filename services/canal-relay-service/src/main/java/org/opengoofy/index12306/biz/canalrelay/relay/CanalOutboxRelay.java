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

import com.alibaba.fastjson2.JSON;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.opengoofy.index12306.biz.canalrelay.config.CanalRelayProperties;
import org.opengoofy.index12306.biz.canalrelay.relay.CanalOutboxMessageRouter.RelayCommand;
import org.opengoofy.index12306.biz.canalrelay.relay.CanalOutboxMessageRouter.RouteResult;
import org.opengoofy.index12306.biz.canalrelay.retry.model.CanalRelayRetryEvent;
import org.opengoofy.index12306.biz.canalrelay.retry.model.CanalRelayRetryStatus;
import org.opengoofy.index12306.biz.canalrelay.retry.service.CanalRelayRetryEventStore;
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
 * <p>每个 Binlog 批次只有所有目标消息已确认或其失败任务已经持久化后才 ACK；失败记录写入异常仍回滚批次。</p>
 */
@Slf4j
@Component
public class CanalOutboxRelay implements SmartLifecycle {

    private final CanalRelayProperties properties;
    private final CanalConnectorFactory connectorFactory;
    private final CanalOutboxMessageRouter messageRouter;
    private final RocketMQTemplate rocketMQTemplate;
    private final Executor publisherExecutor;
    private final CanalRelayRetryEventStore retryEventStore;
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
     * @param retryEventStore 单条失败任务持久化边界
     */
    public CanalOutboxRelay(
            CanalRelayProperties properties,
            CanalConnectorFactory connectorFactory,
            CanalOutboxMessageRouter messageRouter,
            RocketMQTemplate rocketMQTemplate,
            @Qualifier("canalRelayPublisherExecutor") Executor publisherExecutor,
            CanalRelayRetryEventStore retryEventStore) {
        this.properties = properties;
        this.connectorFactory = connectorFactory;
        this.messageRouter = messageRouter;
        this.rocketMQTemplate = rocketMQTemplate;
        this.publisherExecutor = publisherExecutor;
        this.retryEventStore = retryEventStore;
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
     * 投递单个 Canal 批次，确认成功消息或持久化失败任务后推进该批次位点。
     *
     * @param connector 当前 Canal 客户端
     * @param message 已领取、尚未 ACK 的 Canal 批次
     */
    void processBatch(CanalConnector connector, Message message) {
        long batchId = message.getId();
        try {
            List<RouteResult> routeResults = messageRouter.routeIndividually(message.getEntries());
            List<RelayCommand> commands = routeResults.stream()
                    .map(RouteResult::command)
                    .filter(command -> command != null)
                    .toList();
            // 同一批次并行发送；失败结果保留到内存，稍后与解析失败一起原子落库。
            List<PublishOutcome> publishOutcomes = publishAll(commands);
            List<CanalRelayRetryEvent> retryEvents = collectRetryEvents(routeResults, publishOutcomes);
            // 只有失败任务已可靠持久化，Canal 位点推进后才不会丢失单条事件。
            retryEventStore.saveAll(retryEvents);
            connector.ack(batchId);
            if (!commands.isEmpty() || !retryEvents.isEmpty()) {
                log.info("canal_relay_batch_acked batchId={}, commandCount={}, retryCount={}",
                        batchId, commands.size(), retryEvents.size());
            }
        } catch (RuntimeException exception) {
            // ACK 前的路由、发送器异常或失败任务落库异常都必须回滚整批。
            rollbackQuietly(connector, batchId);
            throw exception;
        }
    }

    /**
     * 通过有界线程池并行发送一批已经校验的订单创建命令，返回每条最终结果。
     *
     * @param commands 当前 Canal 批次中的目标命令
     * @return 每条命令的发送结果
     */
    private List<PublishOutcome> publishAll(List<RelayCommand> commands) {
        List<CompletableFuture<PublishOutcome>> futures = new ArrayList<>(commands.size());
        for (RelayCommand command : commands) {
            // 每条消息独立记录最终结果，避免一条失败时丢失同批其他消息已经成功的事实。
            futures.add(CompletableFuture.supplyAsync(() -> publishWithRetry(command), publisherExecutor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException exception) {
            throw new IllegalStateException("RocketMQ 未确认完整 Canal 批次", exception.getCause());
        }
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * 将解析失败和发送耗尽的单条事件转换为统一持久化任务。
     *
     * @param routeResults 路由阶段的逐条结果
     * @param publishOutcomes MQ 发送阶段的逐条结果
     * @return 需要写入失败任务表的事件集合
     */
    private List<CanalRelayRetryEvent> collectRetryEvents(
            List<RouteResult> routeResults,
            List<PublishOutcome> publishOutcomes) {
        List<CanalRelayRetryEvent> retryEvents = new ArrayList<>();
        // 解析或字段校验失败的事件已经由 Router 构造了 DEAD 快照。
        routeResults.stream()
                .map(RouteResult::retryEvent)
                .filter(retryEvent -> retryEvent != null)
                .forEach(retryEvents::add);
        // 快速重试耗尽的命令进入 RETRY 状态，后台阶段再按租约继续发送。
        publishOutcomes.stream()
                .filter(outcome -> !outcome.succeeded())
                .map(this::toRetryEvent)
                .forEach(retryEvents::add);
        return retryEvents;
    }

    /**
     * 将一次发送耗尽结果转换为可恢复的 RETRY 任务。
     *
     * @param outcome 当前命令的最终发送结果
     * @return 待持久化的重试任务
     */
    private CanalRelayRetryEvent toRetryEvent(PublishOutcome outcome) {
        RelayCommand command = outcome.command();
        int initialAttempts = Math.max(1, outcome.attempts());
        int maxAttempts = Math.max(initialAttempts, properties.getRetryMaxAttempts());
        return CanalRelayRetryEvent.builder()
                .retryKey(command.namespace() + ":" + command.eventId())
                .namespace(command.namespace())
                .eventId(command.eventId())
                .commandId(command.commandId())
                .aggregateId(command.aggregateId())
                .destinationTopic(command.destinationTopic())
                .destinationTag(command.destinationTag())
                .delayLevel(command.delayLevel())
                .payload(JSON.toJSONString(command.payload()))
                .status(CanalRelayRetryStatus.RETRY)
                .attemptCount(initialAttempts)
                .maxAttempts(maxAttempts)
                .nextRetryTime(new java.util.Date())
                .lastError(errorSummary(outcome.failure()))
                .build();
    }

    /**
     * 生成不包含订单消息载荷的发送异常摘要。
     *
     * @param exception 最终发送异常
     * @return 异常类型和受限消息文本
     */
    private String errorSummary(RuntimeException exception) {
        if (exception == null) {
            return "unknown";
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return (exception.getClass().getSimpleName() + ": " + message)
                .replace('\n', ' ')
                .replace('\r', ' ');
    }

    /**
     * 在当前批次内独立快速重试一条消息，成功的消息不会因其他消息失败而再次尝试。
     *
     * @param command 已通过 Outbox 主键一致性校验的命令
     * @return 成功或已耗尽快速重试次数的发送结果
     */
    private PublishOutcome publishWithRetry(RelayCommand command) {
        int maxAttempts = Math.max(1, properties.getInitialSendMaxAttempts());
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                publishOne(command);
                return PublishOutcome.success(command, attempt);
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (attempt < maxAttempts) {
                    // 仅当前消息等待，其他发送线程继续处理同批剩余消息。
                    sleepBeforePublishRetry(command, attempt);
                }
            }
        }
        log.warn("canal_relay_command_publish_exhausted eventId={}, commandId={}, attempts={}, exceptionType={}",
                command.eventId(), command.commandId(), maxAttempts,
                lastFailure == null ? null : lastFailure.getClass().getSimpleName());
        return PublishOutcome.failure(command, lastFailure, maxAttempts);
    }

    /**
     * 按尝试次数计算当前消息的快速重试等待时间，并处理中断信号。
     *
     * @param command 当前重试的命令
     * @param attempt 已失败的尝试次数
     */
    private void sleepBeforePublishRetry(RelayCommand command, int attempt) {
        long initial = Math.max(1L, properties.getInitialSendRetryBackoffMillis());
        long maximum = Math.max(initial, properties.getInitialSendRetryMaxBackoffMillis());
        long backoff = Math.min(maximum, initial * (1L << Math.min(attempt - 1, 10)));
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException exception) {
            // 中断表示当前发送任务无法继续，保留中断标志并让批次按失败路径回滚。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RocketMQ 单条快速重试被中断", exception);
        }
        log.debug("canal_relay_command_retry_scheduled eventId={}, commandId={}, attempt={}, backoffMillis={}",
                command.eventId(), command.commandId(), attempt + 1, backoff);
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
     * 表示单条消息在当前 Canal 批次中的最终发送结果。
     *
     * @param command 当前发送命令
     * @param failure 最终失败异常，成功时为空
     * @param attempts 当前消息实际执行的发送次数
     */
    private record PublishOutcome(RelayCommand command, RuntimeException failure, int attempts) {

        /**
         * 创建成功结果。
         *
         * @param command 已发送命令
         * @param attempts 实际发送次数
         * @return 成功结果
         */
        private static PublishOutcome success(RelayCommand command, int attempts) {
            return new PublishOutcome(command, null, attempts);
        }

        /**
         * 创建失败结果。
         *
         * @param command 未确认的命令
         * @param failure 最终失败异常
         * @param attempts 实际发送次数
         * @return 失败结果
         */
        private static PublishOutcome failure(RelayCommand command, RuntimeException failure, int attempts) {
            return new PublishOutcome(command, failure, attempts);
        }

        /**
         * 判断当前消息是否已获得 Broker 确认。
         *
         * @return 没有最终失败异常时返回 true
         */
        private boolean succeeded() {
            return failure == null;
        }
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
