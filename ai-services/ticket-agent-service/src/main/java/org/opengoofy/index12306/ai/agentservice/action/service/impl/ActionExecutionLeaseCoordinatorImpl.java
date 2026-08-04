package org.opengoofy.index12306.ai.agentservice.action.service.impl;

import org.opengoofy.index12306.ai.agentservice.action.config.AgentActionProperties;
import org.opengoofy.index12306.ai.agentservice.action.dto.ClaimedAction;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionExecutionLeaseCoordinator;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionStateService;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionStateService.ExecutionLease;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 使用 Reactor 定时心跳守护同步 MCP 写调用，不额外创建线程池。
 */
@Service
public class ActionExecutionLeaseCoordinatorImpl implements ActionExecutionLeaseCoordinator {

    private final ActionStateService actionStateService;
    private final long heartbeatIntervalMillis;

    /**
     * 创建真实写执行租约协调器。
     *
     * @param actionStateService 操作事务状态服务
     * @param properties 执行租约配置
     */
    public ActionExecutionLeaseCoordinatorImpl(
            ActionStateService actionStateService,
            AgentActionProperties properties) {
        this.actionStateService = actionStateService;
        // 心跳频率取租约三分之一，并设置最小间隔避免错误配置造成高频数据库写入。
        this.heartbeatIntervalMillis = Math.max(100L, properties.executionLease().toMillis() / 3L);
    }

    /**
     * 领取确认事务创建的 QUEUED 执行记录。
     *
     * @param action 已消费确认令牌的操作快照
     * @return 本次执行租约
     */
    @Override
    public ExecutionLease start(ClaimedAction action) {
        // 确认事务已经在通用命令表取得 owner 和 fencing token，此处只让草案进入执行态。
        return actionStateService.startExecution(action.actionId(), action.executionId());
    }

    /**
     * 执行 MCP 调用并在调用存活期间周期续租。
     *
     * @param lease 当前执行租约
     * @param operation 受保护的真实写调用
     * @param <T> 调用结果类型
     * @return 真实写调用结果
     */
    @Override
    public <T> T guard(ExecutionLease lease, Supplier<T> operation) {
        Mono<T> source = Mono.fromCallable(operation::get)
                .subscribeOn(Schedulers.boundedElastic());
        Mono<Void> leaseGuard = Flux.interval(Duration.ofMillis(heartbeatIntervalMillis))
                .concatMap(tick -> renew(lease))
                .then();

        // 租约丢失会取消调用并抛出稳定异常；即使底层调用迟到返回，fencing 校验仍会拒绝终态落库。
        return source.takeUntilOther(leaseGuard).block();
    }

    /**
     * 在弹性线程上执行一次数据库续租。
     *
     * @param lease 当前执行租约
     * @return 续租成功信号
     */
    private Mono<Boolean> renew(ExecutionLease lease) {
        return Mono.fromCallable(() -> actionStateService.heartbeat(lease))
                .subscribeOn(Schedulers.boundedElastic())
                .map(renewed -> {
                    if (!renewed) {
                        throw new IllegalStateException("操作执行权已经失效");
                    }
                    return true;
                });
    }
}
