package org.opengoofy.index12306.ai.agentservice.chat.execution;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.chat.execution.service.TurnRecoveryScheduler;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.stream.service.DurableStreamEventService;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证后台恢复器只使用数据库固化的身份、会话和问题重新进入执行流水线。
 */
class TurnRecoverySchedulerTests {

    /**
     * 验证租约到期候选会被转换为内部恢复命令并完整消费。
     */
    @Test
    void expiredCandidateUsesPersistedTurnData() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        AgentChatPipeline pipeline = mock(AgentChatPipeline.class);
        DurableStreamEventService streamEventService = mock(DurableStreamEventService.class);
        ConversationMemoryService.ExpiredTurnCandidate candidate =
                new ConversationMemoryService.ExpiredTurnCandidate(
                        "user-1", "alice", "conversation-1", "turn-1", "原始问题");
        when(memory.findExpiredTurnCandidates()).thenReturn(List.of(candidate));
        when(pipeline.execute(any())).thenReturn(Flux.empty());
        TurnRecoveryScheduler scheduler = new TurnRecoveryScheduler(memory, pipeline, streamEventService);

        // 调度器会等待本批候选处理完毕，测试可直接检查传给原流水线的恢复命令。
        scheduler.recoverExpiredTurns();

        ArgumentCaptor<ChatCommand> commandCaptor = ArgumentCaptor.forClass(ChatCommand.class);
        verify(pipeline).execute(commandCaptor.capture());
        ChatCommand command = commandCaptor.getValue();
        assertThat(command.turnId()).isEqualTo("turn-1");
        assertThat(command.userId()).isEqualTo("user-1");
        assertThat(command.username()).isEqualTo("alice");
        assertThat(command.conversationId()).isEqualTo("conversation-1");
        assertThat(command.message()).isEqualTo("原始问题");
        assertThat(command.attemptId()).startsWith("recovery-");
    }
}
