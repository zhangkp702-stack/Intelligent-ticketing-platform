package org.opengoofy.index12306.ai.agentservice.chat.execution.service;

import org.opengoofy.index12306.ai.agentservice.chat.config.AgentTurnProperties;
import org.opengoofy.index12306.ai.agentservice.chat.execution.exception.ExecutionLeaseLostException;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * 使用数据库心跳维持 Turn 执行权，并把跨实例取消或接管转换为响应式终止信号。
 */
@Service
public class TurnLeaseHeartbeatCoordinator {

    private final ConversationMemoryService conversationMemoryService;
    private final long heartbeatIntervalMillis;

    /**
     * 创建轮次租约心跳协调器。
     *
     * @param conversationMemoryService 轮次状态和续租服务
     * @param turnProperties 轮次执行租约配置
     */
    public TurnLeaseHeartbeatCoordinator(
            ConversationMemoryService conversationMemoryService,
            AgentTurnProperties turnProperties) {
        this.conversationMemoryService = conversationMemoryService;
        // 心跳沿用 Reactor 调度器，不为每个服务实例额外维护线程池和销毁生命周期。
        long leaseMillis = turnProperties.executionLease().toMillis();
        this.heartbeatIntervalMillis = Math.max(100L, leaseMillis / 3L);
    }

    /**
     * 在源事件流存活期间周期续租；执行权丢失时取消上游并返回稳定异常。
     *
     * @param context 当前执行权上下文
     * @param terminal 当前流水线是否已经完成数据库终态写入
     * @param source 需要受租约保护的事件流
     * @param <T> 事件类型
     * @return 受数据库租约保护的事件流
     */
    public <T> Flux<T> guard(
            AgentRequestContext context,
            BooleanSupplier terminal,
            Flux<T> source) {
        return Flux.defer(() -> {
            // companion 流只负责续租和发出失效错误，源流结束时 Reactor 会自动取消它。
            Mono<Void> leaseGuard = Flux.interval(Duration.ofMillis(heartbeatIntervalMillis))
                    .filter(tick -> !terminal.getAsBoolean())
                    .concatMap(tick -> renewLease(context))
                    .then();
            return source.takeUntilOther(leaseGuard);
        });
    }

    /**
     * 在弹性线程上完成一次数据库续租，并把未知执行权统一转换为失效异常。
     *
     * @param context 当前执行权上下文
     * @return 成功续租信号
     */
    private Mono<Boolean> renewLease(AgentRequestContext context) {
        return Mono.fromCallable(() -> conversationMemoryService.heartbeatTurn(
                        context.userId(),
                        context.turnId(),
                        context.executionOwner(),
                        context.fencingToken()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(renewed -> {
                    if (!renewed) {
                        throw new ExecutionLeaseLostException(
                                "轮次执行权已经失效，停止旧执行者继续处理");
                    }
                    return true;
                })
                .onErrorMap(exception -> exception instanceof ExecutionLeaseLostException
                        ? exception
                        : new ExecutionLeaseLostException(
                                "无法续租轮次执行权，已停止当前处理", exception));
    }
}
