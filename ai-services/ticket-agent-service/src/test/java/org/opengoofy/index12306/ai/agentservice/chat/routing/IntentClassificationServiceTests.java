package org.opengoofy.index12306.ai.agentservice.chat.routing;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentClassificationService.IntentModelOutput;
import org.opengoofy.index12306.ai.agentservice.conversation.context.AgentChatMessage;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationHistoryContext;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelRole;
import org.opengoofy.index12306.ai.agentservice.infra.model.observability.ModelAttemptContext;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.ModelCallResult;
import org.opengoofy.index12306.ai.agentservice.infra.model.structured.StructuredModelInvoker;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证轻量意图模型的结构化调用边界和枚举转换。
 */
class IntentClassificationServiceTests {

    /**
     * 验证口语化购票请求通过专用小模型角色得到购票意图。
     */
    @Test
    void conversationalPurchaseRequestUsesIntentModel() {
        StructuredModelInvoker invoker = mock(StructuredModelInvoker.class);
        IntentClassificationService service = new IntentClassificationService(invoker);
        when(invoker.call(eq(ModelRole.INTENT_CLASSIFICATION), any(), any(), eq(IntentModelOutput.class), any()))
                .thenReturn(new ModelCallResult<>(
                        new IntentModelOutput("ticket_purchase"),
                        "bailian-flash",
                        "bailian",
                        "qwen3.5-flash-2026-02-23",
                        0,
                        Duration.ofMillis(30),
                        "model-call-1"));

        // 该表达不含“购票”或“买票”固定词组，分类结果仍完全由模型结构化输出决定。
        AgentIntent intent = service.classify(
                "买下午一点的一等座",
                history("买下午一点的一等座"),
                "",
                attemptContext());

        assertThat(intent).isEqualTo(AgentIntent.TICKET_PURCHASE);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(invoker).call(
                eq(ModelRole.INTENT_CLASSIFICATION),
                promptCaptor.capture(),
                any(),
                eq(IntentModelOutput.class),
                any());
        assertThat(promptCaptor.getValue().getInstructions().get(
                promptCaptor.getValue().getInstructions().size() - 1))
                .isInstanceOfSatisfying(UserMessage.class, message ->
                        assertThat(message.getText()).isEqualTo("买下午一点的一等座"));
    }

    /**
     * 创建仅包含当前问题的分类测试上下文。
     *
     * @param question 当前用户问题
     * @return 可用于分类服务的最小会话上下文
     */
    private ConversationHistoryContext history(String question) {
        // 分类边界测试不依赖历史摘要，只保留符合领域约束的用户消息。
        return new ConversationHistoryContext(
                "conversation-1", null, null, null, null, 0,
                List.of(), AgentChatMessage.user(question), List.of(), null, null, 10);
    }

    /**
     * 创建固定的模型审计上下文。
     *
     * @return 当前分类请求的审计标识
     */
    private ModelAttemptContext attemptContext() {
        // 固定标识便于确认分类调用与当前对话轮次相关联。
        return new ModelAttemptContext("request-1", "conversation-1", "turn-1");
    }
}
