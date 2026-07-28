package org.opengoofy.index12306.ai.agentservice.chat.routing;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentClassificationService.CancellationIntentData;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentClassificationService.IntentClassificationResult;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentClassificationService.IntentModelOutput;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentClassificationService.RefundIntentData;
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
     * 验证退票意图和模型从最近上下文提取的订单字段会一起交给固定代码链。
     */
    @Test
    void refundRequestReturnsStructuredActionData() {
        StructuredModelInvoker invoker = mock(StructuredModelInvoker.class);
        IntentClassificationService service = new IntentClassificationService(invoker);
        RefundIntentData refundRequest = new RefundIntentData(
                null, "G9001", "2026-07-28", List.of("万重山"));
        when(invoker.call(eq(ModelRole.INTENT_CLASSIFICATION), any(), any(), eq(IntentModelOutput.class), any()))
                .thenReturn(new ModelCallResult<>(
                        new IntentModelOutput("ticket_refund", null, null, refundRequest),
                        "bailian-flash",
                        "bailian",
                        "qwen3.5-flash-2026-02-23",
                        0,
                        Duration.ofMillis(30),
                        "model-call-2"));

        // 分类模型一次返回意图和字段，退票代码链不再请求回答模型补充或选择工具。
        IntentClassificationResult result = service.classifyWithActionData(
                "给万重山退掉刚才 G9001 的票",
                history("给万重山退掉刚才 G9001 的票"),
                "",
                attemptContext());

        assertThat(result.intent()).isEqualTo(AgentIntent.TICKET_REFUND);
        assertThat(result.refundRequest()).isEqualTo(refundRequest);
        assertThat(result.purchaseRequest()).isNull();
        assertThat(result.cancellationRequest()).isNull();
    }

    /**
     * 验证指定乘车人的“取消”表达即使被模型识别为整单取消，也会由代码校正为部分退票。
     */
    @Test
    void passengerSpecificCancellationIsNormalizedToRefund() {
        StructuredModelInvoker invoker = mock(StructuredModelInvoker.class);
        IntentClassificationService service = new IntentClassificationService(invoker);
        CancellationIntentData cancellationRequest = new CancellationIntentData(
                null, "G9001", "2026-07-28", List.of("万重山"));
        when(invoker.call(eq(ModelRole.INTENT_CLASSIFICATION), any(), any(), eq(IntentModelOutput.class), any()))
                .thenReturn(new ModelCallResult<>(
                        new IntentModelOutput(
                                "order_cancellation", null, cancellationRequest, null),
                        "bailian-flash",
                        "bailian",
                        "qwen3.5-flash-2026-02-23",
                        0,
                        Duration.ofMillis(30),
                        "model-call-3"));

        // 姓名仍由模型从自然语言和上下文提取，代码只负责执行不可变的业务分流规则。
        IntentClassificationResult result = service.classifyWithActionData(
                "把万重山的票取消掉",
                history("把万重山的票取消掉"),
                "",
                attemptContext());

        assertThat(result.intent()).isEqualTo(AgentIntent.TICKET_REFUND);
        assertThat(result.cancellationRequest()).isNull();
        assertThat(result.refundRequest()).isEqualTo(
                new RefundIntentData(null, "G9001", "2026-07-28", List.of("万重山")));
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
