package org.opengoofy.index12306.ai.agentservice.chat.execution.service;

import org.opengoofy.index12306.ai.agentservice.chat.execution.AgentChatPipeline;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.stream.service.DurableStreamEventService;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService.ExpiredTurnCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫描租约已经过期的运行中 Turn，并通过原流水线从持久化任务检查点恢复执行。
 */
@Service
@ConditionalOnProperty(
        prefix = "index12306.agent.turn",
        name = "recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TurnRecoveryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TurnRecoveryScheduler.class);
    private final ConversationMemoryService conversationMemoryService;
    private final AgentChatPipeline agentChatPipeline;
    private final DurableStreamEventService durableStreamEventService;
    private final AtomicBoolean scanning = new AtomicBoolean();

    /**
     * 创建过期轮次恢复器。
     *
     * @param conversationMemoryService 过期轮次查询和接管服务
     * @param agentChatPipeline 可复用持久化计划的完整执行流水线
     * @param durableStreamEventService 恢复过程产生的 SSE 事件持久化服务
     */
    public TurnRecoveryScheduler(
            ConversationMemoryService conversationMemoryService,
            AgentChatPipeline agentChatPipeline,
            DurableStreamEventService durableStreamEventService) {
        this.conversationMemoryService = conversationMemoryService;
        this.agentChatPipeline = agentChatPipeline;
        this.durableStreamEventService = durableStreamEventService;
    }

    /**
     * 周期扫描并尝试恢复过期 Turn；多实例竞争最终由数据库行锁和 fencing token 裁决。
     */
    @Scheduled(fixedDelayString = "${index12306.agent.turn.recovery-interval-millis:5000}")
    public void recoverExpiredTurns() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            // 扫描结果只是候选快照，开始执行时 startTurn 会再次检查租约并原子接管。
            List<ExpiredTurnCandidate> candidates = conversationMemoryService.findExpiredTurnCandidates();
            Flux.fromIterable(candidates)
                    .flatMap(this::recoverCandidate, 4)
                    .blockLast();
        } finally {
            scanning.set(false);
        }
    }

    /**
     * 为单个候选构造内部命令并完整消费 SSE 流，使终态持久化不依赖在线客户端。
     *
     * @param candidate 首次提交时固化的恢复快照
     * @return 恢复完成信号；竞争失败或单轮异常不会中断其他候选
     */
    private Mono<Void> recoverCandidate(ExpiredTurnCandidate candidate) {
        return Mono.defer(() -> {
                    // 内部 attemptId 仅用于本次恢复观测，turnId 和问题内容仍取自数据库。
                    ChatCommand command = new ChatCommand(
                            candidate.turnId(),
                            "recovery-" + UUID.randomUUID(),
                            "internal-recovery",
                            candidate.userId(),
                            candidate.username(),
                            candidate.conversationId(),
                            candidate.content());
                    // 后台恢复没有在线 HTTP 消费者，仍需按顺序落库供任意实例续传。
                    return agentChatPipeline.execute(command)
                            .concatMap(event -> Mono.defer(() -> Mono.justOrEmpty(
                                    durableStreamEventService.append(candidate.userId(), event))))
                            .then(Mono.defer(() -> Mono.justOrEmpty(
                                            durableStreamEventService.ensureTerminal(
                                                    candidate.userId(), candidate.turnId()))
                                    .then()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(exception -> {
                    // 并发实例已先接管属于正常竞争，其他异常保留 turnId 便于后续再次扫描。
                    LOGGER.warn("恢复过期Agent轮次失败，turnId={}", candidate.turnId(), exception);
                    return Mono.empty();
                });
    }
}
