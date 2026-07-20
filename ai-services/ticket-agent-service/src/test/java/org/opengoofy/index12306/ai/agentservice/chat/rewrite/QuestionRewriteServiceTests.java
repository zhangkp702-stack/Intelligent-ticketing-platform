package org.opengoofy.index12306.ai.agentservice.chat.rewrite;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.chat.rewrite.QuestionRewriteService.QuestionRewriteResult;
import org.opengoofy.index12306.ai.agentservice.chat.rewrite.QuestionRewriteService.RewriteModelOutput;
import org.opengoofy.index12306.ai.agentservice.memory.context.AgentChatMessage;
import org.opengoofy.index12306.ai.agentservice.memory.context.ConversationHistoryContext;
import org.opengoofy.index12306.ai.agentservice.memory.context.ConversationTurnContext;
import org.opengoofy.index12306.ai.agentservice.model.observability.ModelAttemptContext;
import org.opengoofy.index12306.ai.agentservice.model.routing.ModelCallResult;
import org.opengoofy.index12306.ai.agentservice.model.structured.StructuredModelInvoker;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证问题改写只对依赖历史的短问题增加模型调用。
 */
class QuestionRewriteServiceTests {

    /**
     * 验证明确信息完整的问题直接进入回答阶段。
     */
    @Test
    void standaloneQuestionSkipsRewriteModel() {
        StructuredModelInvoker invoker = mock(StructuredModelInvoker.class);
        QuestionRewriteService service = new QuestionRewriteService(invoker);
        ConversationHistoryContext history = history("查询后天北京到上海的二等座余票");

        // 完整问题即使存在历史轮次，也不应增加一次模型网络调用。
        QuestionRewriteResult result = service.rewrite(history, attemptContext());

        assertThat(result.effectiveQuestion()).isEqualTo("查询后天北京到上海的二等座余票");
        assertThat(result.modelInvoked()).isFalse();
        assertThat(result.rewritten()).isFalse();
        verify(invoker, never()).call(any(), any(), any(), any(), any());
    }

    /**
     * 验证带序号指代的短问题会结合最近轮次改写。
     */
    @Test
    void contextualShortQuestionIsRewritten() {
        StructuredModelInvoker invoker = mock(StructuredModelInvoker.class);
        QuestionRewriteService service = new QuestionRewriteService(invoker);
        ConversationHistoryContext history = history("第二个呢");
        when(invoker.call(any(), any(), any(), any(), any())).thenReturn(new ModelCallResult<>(
                new RewriteModelOutput("明天北京到上海的第二趟车 G9003 还有票吗"),
                "bailian-flash",
                "bailian",
                "qwen3.5-flash-2026-02-23",
                0,
                Duration.ofMillis(80),
                "model-call-1"));

        // 省略问题经轻量模型补全后，回答模型使用独立问题。
        QuestionRewriteResult result = service.rewrite(history, attemptContext());

        assertThat(result.originalQuestion()).isEqualTo("第二个呢");
        assertThat(result.effectiveQuestion())
                .isEqualTo("明天北京到上海的第二趟车 G9003 还有票吗");
        assertThat(result.modelInvoked()).isTrue();
        assertThat(result.rewritten()).isTrue();

        // 改写提示保留最近完整问答，并把当前问题放在最后。
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(invoker).call(any(), promptCaptor.capture(), any(), any(), any());
        assertThat(promptCaptor.getValue().getInstructions().get(
                promptCaptor.getValue().getInstructions().size() - 1))
                .isInstanceOfSatisfying(UserMessage.class, message ->
                        assertThat(message.getText()).isEqualTo("第二个呢"));
    }

    /**
     * 验证改写模型失败不会阻断主回答链路。
     */
    @Test
    void rewriteFailureFallsBackToOriginalQuestion() {
        StructuredModelInvoker invoker = mock(StructuredModelInvoker.class);
        QuestionRewriteService service = new QuestionRewriteService(invoker);
        ConversationHistoryContext history = history("换成一等座");
        when(invoker.call(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("model unavailable"));

        // 增强阶段失败时保留用户原始表达，由主回答模型结合完整历史继续处理。
        QuestionRewriteResult result = service.rewrite(history, attemptContext());

        assertThat(result.effectiveQuestion()).isEqualTo("换成一等座");
        assertThat(result.modelInvoked()).isTrue();
        assertThat(result.rewritten()).isFalse();
    }

    /**
     * 创建包含一轮完整历史和指定当前问题的测试上下文。
     *
     * @param currentQuestion 当前用户问题
     * @return 会话历史上下文
     */
    private ConversationHistoryContext history(String currentQuestion) {
        ConversationTurnContext turn = new ConversationTurnContext(
                "turn-history",
                AgentChatMessage.user("查询明天北京到上海的车票"),
                AgentChatMessage.assistant("第一趟 G9001，第二趟 G9003"));
        // 其他摘要字段与本单元测试无关，仅保留完整历史轮次结构。
        return new ConversationHistoryContext(
                "conversation-1", null, null, null, null, 0,
                List.of(turn), AgentChatMessage.user(currentQuestion),
                List.of("message-1", "message-2"), 1L, 2L, 20);
    }

    /**
     * 创建稳定的模型调用审计上下文。
     *
     * @return 模型调用审计上下文
     */
    private ModelAttemptContext attemptContext() {
        // 固定标识便于验证失败日志与回答请求保持关联。
        return new ModelAttemptContext("request-1", "conversation-1", "turn-current");
    }
}
