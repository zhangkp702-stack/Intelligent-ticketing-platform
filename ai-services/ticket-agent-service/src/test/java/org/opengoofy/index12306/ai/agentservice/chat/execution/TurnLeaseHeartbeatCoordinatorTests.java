package org.opengoofy.index12306.ai.agentservice.chat.execution;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentTurnProperties;
import org.opengoofy.index12306.ai.agentservice.chat.execution.exception.ExecutionLeaseLostException;
import org.opengoofy.index12306.ai.agentservice.chat.execution.service.TurnLeaseHeartbeatCoordinator;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证数据库心跳能够把跨实例取消或接管转换为当前响应流的停止信号。
 */
class TurnLeaseHeartbeatCoordinatorTests {

    /**
     * 验证续租失败后取消仍在运行的上游并返回执行权失效异常。
     */
    @Test
    void leaseLossCancelsRunningStream() throws Exception {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        AgentRequestContext context = new AgentRequestContext(
                "turn-1", "user-1", "alice", "conversation-1", "turn-1", "owner-1", 7L);
        when(memory.heartbeatTurn("user-1", "turn-1", "owner-1", 7L)).thenReturn(false);
        TurnLeaseHeartbeatCoordinator coordinator = new TurnLeaseHeartbeatCoordinator(
                memory,
                new AgentTurnProperties("test-secret", Duration.ofMinutes(1), Duration.ofMillis(300)));
        // 无限流模拟仍在等待模型或下游响应，数据库取消后必须由心跳主动中止。
        Flux<Long> guarded = coordinator.guard(
                context,
                () -> false,
                Flux.interval(Duration.ofMillis(20)));
        StepVerifier.create(guarded)
                .thenConsumeWhile(value -> true)
                .expectError(ExecutionLeaseLostException.class)
                .verify(Duration.ofSeconds(2));
    }
}
