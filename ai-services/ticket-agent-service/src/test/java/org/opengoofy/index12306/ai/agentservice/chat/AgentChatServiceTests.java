package org.opengoofy.index12306.ai.agentservice.chat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionService;
import org.opengoofy.index12306.ai.agentservice.action.domain.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.EventType;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentChatProperties;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageType;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationContextService;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.model.routing.RoutedChatModelService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证对话编排直接加载会话上下文、完成流式回答和复用幂等结果。
 */
class AgentChatServiceTests {

    /**
     * 验证新问题不经过主题判断即可加载会话上下文并完成流式回答。
     */
    @Test
    void newTurnLoadsConversationContextStreamsAndCompletes() {
        TestContext test = context();
        ChatCommand command = command();
        ConversationContextService.ConversationContext conversationContext = new ConversationContextService.ConversationContext(
                command.conversationId(), null, null, null, null, 0,
                List.of(new ConversationContextService.ContextMessage(
                        "message-1", 1L, MessageRole.USER, MessageType.TEXT, command.message(), 3)),
                3);

        // 模拟新轮次和会话级上下文，回答模型返回两个流式增量。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        when(test.contextService().load(command.userId(), command.requestId(), command.conversationId()))
                .thenReturn(conversationContext);
        ChatResponse firstResponse = response("北京到");
        ChatResponse secondResponse = response("上海有票");
        when(test.model().stream(any(), any(), any(), eq(false))).thenReturn(
                Flux.just(firstResponse, secondResponse));

        StepVerifier.create(test.service().stream(command))
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.type()).isEqualTo(EventType.META))
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.delta()).isEqualTo("北京到"))
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.delta()).isEqualTo("上海有票"))
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.type()).isEqualTo(EventType.DONE);
                    org.assertj.core.api.Assertions.assertThat(event.content()).isEqualTo("北京到上海有票");
                })
                .verifyComplete();
        verify(test.memory()).completeTurn(any());
        verify(test.contextService()).load(command.userId(), command.requestId(), command.conversationId());

        // 捕获实际发送给模型的提示，确认独立只读工具可以在同一模型轮次中批量请求。
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model(), atLeastOnce()).stream(any(), promptCaptor.capture(), any(), eq(false));
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getParallelToolCalls()).isTrue();

        // 首事件与首个正文指标都应记录一次，避免性能优化破坏现有观测口径。
        assertThat(test.meterRegistry()
                .get("agent.chat.time.to.first.event").timer().count()).isEqualTo(1);
        assertThat(test.meterRegistry()
                .get("agent.chat.time.to.first.token").timer().count()).isEqualTo(1);
    }

    /**
     * 验证购票草案真实落库后由服务端收口最终正文，模型不能把待确认草案描述为已下单。
     */
    @Test
    void persistedPurchaseDraftOverridesModelSuccessClaim() {
        TestContext test = context();
        ChatCommand command = command();
        ConversationContextService.ConversationContext conversationContext = new ConversationContextService.ConversationContext(
                command.conversationId(), null, null, null, null, 0, List.of(), 0);
        ActionConfirmationView action = new ActionConfirmationView(
                "action-1", "TICKET_PURCHASE", AgentActionStatus.AWAITING_CONFIRMATION,
                "购买 G9004 次列车", Instant.parse("2026-07-16T00:10:00Z"), "confirmation-token");
        ChatResponse incorrectModelResponse = response("订单已提交成功，请前往支付");

        // 模拟模型错误声称已提交订单，但本轮数据库中实际只有待确认购票草案。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        when(test.contextService().load(command.userId(), command.requestId(), command.conversationId()))
                .thenReturn(conversationContext);
        when(test.model().stream(any(), any(), any(), eq(false)))
                .thenReturn(Flux.just(incorrectModelResponse));
        when(test.purchaseActionService().confirmationForTurn(command.userId(), "turn-1"))
                .thenReturn(Optional.of(action));

        // 最终事件和持久化正文必须以数据库状态为准，同时保留结构化待确认动作。
        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo(EventType.ACTION_REQUIRED);
                    assertThat(event.action()).isEqualTo(action);
                })
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo(EventType.DONE);
                    assertThat(event.content()).contains("点击“确认下单”按钮");
                    assertThat(event.content()).contains("尚未创建订单");
                    assertThat(event.content()).doesNotContain("订单已提交成功");
                })
                .verifyComplete();

        ArgumentCaptor<ConversationMemoryService.CompleteTurnCommand> completionCaptor =
                ArgumentCaptor.forClass(ConversationMemoryService.CompleteTurnCommand.class);
        verify(test.memory()).completeTurn(completionCaptor.capture());
        assertThat(completionCaptor.getValue().content()).contains("尚未创建订单");
    }

    /**
     * 验证已完成幂等请求直接重放，不再加载上下文或调用模型。
     */
    @Test
    void completedTurnIsReusedWithoutModelCall() {
        TestContext test = context();
        ChatCommand command = command();
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, false));
        when(test.memory().getTurnState(command.userId(), "turn-1")).thenReturn(
                new ConversationMemoryService.TurnState(TurnStatus.COMPLETED, "已完成的回答"));

        StepVerifier.create(test.service().stream(command))
                .expectNextCount(2)
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.type()).isEqualTo(EventType.DONE);
                    org.assertj.core.api.Assertions.assertThat(event.reused()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(event.content()).isEqualTo("已完成的回答");
                })
                .verifyComplete();
        verify(test.contextService(), never()).load(any(), any(), any());
        verify(test.model(), never()).stream(any(), any(), any(), any(Boolean.class));
    }

    /**
     * 验证模型长期无数据时终止事件流并取消仍在运行的轮次。
     */
    @Test
    void responseTimeoutCancelsRunningTurn() {
        TestContext test = context(Duration.ofMillis(30));
        ChatCommand command = command();
        ConversationContextService.ConversationContext conversationContext = new ConversationContextService.ConversationContext(
                command.conversationId(), null, null, null, null, 0, List.of(), 0);
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        when(test.contextService().load(command.userId(), command.requestId(), command.conversationId()))
                .thenReturn(conversationContext);
        when(test.model().stream(any(), any(), any(), eq(false))).thenReturn(Flux.never());

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
                        .isInstanceOf(AgentChatException.class))
                .verify(Duration.ofSeconds(1));
        verify(test.memory()).cancelTurn(command.userId(), "turn-1");
    }

    /**
     * 创建不连接真实模型和工具的编排测试上下文。
     *
     * @return 编排服务及依赖替身
     */
    private TestContext context() {
        return context(Duration.ofSeconds(60));
    }

    /**
     * 使用指定超时创建编排测试上下文。
     *
     * @param responseTimeout 整轮响应超时
     * @return 编排服务及依赖替身
     */
    @SuppressWarnings("unchecked")
    private TestContext context(Duration responseTimeout) {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        ConversationContextService contextService = mock(ConversationContextService.class);
        RoutedChatModelService model = mock(RoutedChatModelService.class);
        PurchaseActionService purchaseActionService = mock(PurchaseActionService.class);
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.empty());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentChatService service = new AgentChatService(
                memory,
                contextService,
                model,
                purchaseActionService,
                new McpToolContextFactory(),
                providers,
                Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC),
                new AgentChatProperties(responseTimeout),
                new AgentChatMetrics(meterRegistry));
        return new TestContext(service, memory, contextService, model, purchaseActionService, meterRegistry);
    }

    /**
     * 创建稳定的测试对话命令。
     *
     * @return 测试命令
     */
    private ChatCommand command() {
        return new ChatCommand(
                "request-1", "request-1", "user-1", "tester",
                "conversation-1", "查询北京到上海的票");
    }

    /**
     * 创建包含指定文本增量的模型响应。
     *
     * @param text 文本增量
     * @return 模型响应替身
     */
    private ChatResponse response(String text) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(new AssistantMessage(text));
        return response;
    }

    /**
     * 单个编排测试所需依赖集合。
     *
     * @param service 待测服务
     * @param memory 会话记忆替身
     * @param contextService 会话上下文替身
     * @param model 回答模型替身
     * @param purchaseActionService 购票动作服务替身
     * @param meterRegistry 指标注册表
     */
    private record TestContext(
            AgentChatService service,
            ConversationMemoryService memory,
            ConversationContextService contextService,
            RoutedChatModelService model,
            PurchaseActionService purchaseActionService,
            SimpleMeterRegistry meterRegistry) {
    }
}
