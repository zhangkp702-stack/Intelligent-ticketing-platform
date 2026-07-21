package org.opengoofy.index12306.ai.agentservice.chat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.action.ActionDraftCreationTracker;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionService;
import org.opengoofy.index12306.ai.agentservice.action.domain.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.EventType;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentChatProperties;
import org.opengoofy.index12306.ai.agentservice.chat.rewrite.QuestionRewriteService;
import org.opengoofy.index12306.ai.agentservice.chat.rewrite.QuestionRewriteService.QuestionRewriteResult;
import org.opengoofy.index12306.ai.agentservice.chat.routing.QuestionToolRoutingService;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.memory.context.AgentChatMessage;
import org.opengoofy.index12306.ai.agentservice.memory.context.ConversationHistoryContext;
import org.opengoofy.index12306.ai.agentservice.memory.context.ConversationTurnContext;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationContextService;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.model.routing.RoutedChatModelService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
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
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(),
                command.message(),
                List.of(new ConversationTurnContext(
                        "history-turn",
                        AgentChatMessage.user("上一轮问题"),
                        AgentChatMessage.assistant("上一轮回答"))));

        // 模拟新轮次和会话级上下文，回答模型返回两个流式增量。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
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
        verify(test.contextService()).load(
                command.userId(), command.requestId(), command.conversationId(),
                "turn-1", "message-1", 1L, command.message());
        verify(test.purchaseActionService(), never()).confirmationForTurn(any(), any());

        // 捕获实际发送给模型的提示，确认独立只读工具可以在同一模型轮次中批量请求。
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model(), atLeastOnce()).stream(any(), promptCaptor.capture(), any(), eq(false));
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getParallelToolCalls()).isTrue();
        assertThat(promptCaptor.getValue().getInstructions())
                .filteredOn(message -> message instanceof UserMessage)
                .extracting(message -> message.getText())
                .containsExactly("上一轮问题", command.message());

        // 首事件与首个正文指标都应记录一次，避免性能优化破坏现有观测口径。
        assertThat(test.meterRegistry()
                .get("agent.chat.time.to.first.event").timer().count()).isEqualTo(1);
        assertThat(test.meterRegistry()
                .get("agent.chat.time.to.first.token").timer().count()).isEqualTo(1);
    }

    /**
     * 验证上下文相关短问题使用改写后的独立问题调用回答模型。
     */
    @Test
    void contextualQuestionUsesRewrittenStandaloneQuestion() {
        TestContext test = context();
        ChatCommand command = new ChatCommand(
                "request-2", "request-2", "user-1", "tester",
                "conversation-1", "第二个呢");
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(),
                command.message(),
                List.of(new ConversationTurnContext(
                        "history-turn",
                        AgentChatMessage.user("查询明天北京到上海的车票"),
                        AgentChatMessage.assistant("第一趟 G9001，第二趟 G9003"))));

        // 模拟改写阶段把省略问句补全，回答模型仍只执行一次正式回答调用。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
        doReturn(new QuestionRewriteResult(
                command.message(),
                "明天北京到上海的第二趟车 G9003 还有票吗",
                true,
                true))
                .when(test.questionRewriteService())
                .rewrite(eq(conversationHistory), any());
        ChatResponse modelResponse = response("G9003 还有余票");
        when(test.model().stream(any(), any(), any(), eq(false)))
                .thenReturn(Flux.just(modelResponse));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        // Prompt 最后一条用户消息应为独立问题，原始省略问句不重复进入回答模型输入。
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model()).stream(any(), promptCaptor.capture(), any(), eq(false));
        assertThat(promptCaptor.getValue().getInstructions().get(
                promptCaptor.getValue().getInstructions().size() - 1))
                .isInstanceOfSatisfying(UserMessage.class, message ->
                        assertThat(message.getText())
                                .isEqualTo("明天北京到上海的第二趟车 G9003 还有票吗"));
    }

    /**
     * 验证购票草案真实落库后由服务端收口最终正文，模型不能把待确认草案描述为已下单。
     */
    @Test
    void persistedPurchaseDraftOverridesModelSuccessClaim() {
        TestContext test = context();
        ChatCommand command = command();
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(), command.message(), List.of());
        ActionConfirmationView action = new ActionConfirmationView(
                "action-1", "TICKET_PURCHASE", AgentActionStatus.AWAITING_CONFIRMATION,
                "购买 G9004 次列车", Instant.parse("2026-07-16T00:10:00Z"), "confirmation-token");
        ChatResponse incorrectModelResponse = response("订单已提交成功，请前往支付");

        // 模拟模型错误声称已提交订单，但本轮数据库中实际只有待确认购票草案。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
        when(test.model().stream(any(), any(), any(), eq(false)))
                .thenReturn(Flux.just(incorrectModelResponse));
        when(test.purchaseActionService().confirmationForTurn(command.userId(), "turn-1"))
                .thenReturn(Optional.of(action));
        test.actionDraftCreationTracker().markCreated("turn-1");

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
        verify(test.purchaseActionService()).confirmationForTurn(command.userId(), "turn-1");
        assertThat(test.actionDraftCreationTracker().consumeCreated("turn-1")).isFalse();
    }

    /**
     * 验证回答模型只获得普通查询和草案工具，真实购票写工具即使被错误注册也会被编排层拒绝。
     */
    @Test
    void answerModelReceivesOnlyWhitelistedTools() {
        ToolCallback queryTool = toolCallback("query_tickets");
        ToolCallback draftTool = toolCallback("prepare_ticket_purchase");
        ToolCallback writeTool = toolCallback("execute_confirmed_ticket_purchase");
        ToolCallbackProvider provider = ToolCallbackProvider.from(queryTool, draftTool, writeTool);
        TestContext test = context(Duration.ofSeconds(60), provider);
        ChatCommand command = new ChatCommand(
                "request-purchase", "request-purchase", "user-1", "tester",
                "conversation-1", "帮我购买明天北京到上海的二等座车票");
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(), command.message(), List.of());
        ChatResponse modelResponse = response("可以直接回答，也可以按需查询实时数据");

        // 模拟一次购票业务问答，模型本身不产生任何工具调用。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
        when(test.model().stream(any(), any(), any(), eq(true))).thenReturn(Flux.just(modelResponse));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        // 捕获最终提示选项，确认查询和草案工具保留，而真实写工具不进入模型上下文。
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model()).stream(any(), promptCaptor.capture(), any(), eq(true));
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("query_tickets", "prepare_ticket_purchase");
    }

    /**
     * 验证普通问答即使存在工具提供器也不会向回答模型注册 MCP 工具。
     */
    @Test
    void ordinaryQuestionSkipsMcpTools() {
        ToolCallback queryTool = toolCallback("query_tickets");
        ToolCallbackProvider provider = ToolCallbackProvider.from(queryTool);
        TestContext test = context(Duration.ofSeconds(60), provider);
        ChatCommand command = new ChatCommand(
                "request-chat", "request-chat", "user-1", "tester",
                "conversation-1", "你好，请介绍一下你自己");
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(), command.message(), List.of());
        ChatResponse modelResponse = response("你好，我是 12306 购票智能体助手");

        // 普通问答仍经过统一编排和持久化，但模型调用不携带任何工具定义。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
        when(test.model().stream(any(), any(), any(), eq(false)))
                .thenReturn(Flux.just(modelResponse));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model()).stream(any(), promptCaptor.capture(), any(), eq(false));
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks()).isEmpty();
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
        verify(test.contextService(), never()).load(
                any(), any(), any(), any(), any(), any(Long.class), any());
        verify(test.model(), never()).stream(any(), any(), any(), any(Boolean.class));
    }

    /**
     * 验证模型长期无数据时终止事件流并取消仍在运行的轮次。
     */
    @Test
    void responseTimeoutCancelsRunningTurn() {
        TestContext test = context(Duration.ofMillis(30));
        ChatCommand command = command();
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(), command.message(), List.of());
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
        when(test.model().stream(any(), any(), any(), eq(false))).thenReturn(Flux.never());
        test.actionDraftCreationTracker().markCreated("turn-1");

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
                        .isInstanceOf(AgentChatException.class))
                .verify(Duration.ofSeconds(1));
        verify(test.memory()).cancelTurn(command.userId(), "turn-1");
        assertThat(test.actionDraftCreationTracker().consumeCreated("turn-1")).isFalse();
    }

    /**
     * 创建不包含当前问题的历史上下文。
     *
     * @param conversationId 会话标识
     * @param currentQuestion 当前用户问题
     * @param turns 已完成的历史轮次
     * @return 会话历史上下文
     */
    private ConversationHistoryContext history(
            String conversationId,
            String currentQuestion,
            List<ConversationTurnContext> turns) {
        // 测试历史只关注完整轮次结构，摘要和快照边界保持为空。
        return new ConversationHistoryContext(
                conversationId, null, null, null, null, 0,
                turns, AgentChatMessage.user(currentQuestion), List.of(), null, null, 0);
    }

    /**
     * 为新轮次模拟历史上下文加载结果。
     *
     * @param test 编排测试上下文
     * @param command 当前对话命令
     * @param history 不包含当前问题的历史上下文
     */
    private void stubHistory(
            TestContext test,
            ChatCommand command,
            ConversationHistoryContext history) {
        // 当前问题和持久化消息信息独立传入加载器，不预先塞入历史轮次。
        when(test.contextService().load(
                command.userId(), command.requestId(), command.conversationId(),
                "turn-1", "message-1", 1L, command.message()))
                .thenReturn(history);
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
        // 默认测试不注册任何工具，覆盖普通问答直接由模型生成正文的路径。
        return context(responseTimeout, new ToolCallbackProvider[0]);
    }

    /**
     * 使用指定超时和工具提供器创建编排测试上下文。
     *
     * @param responseTimeout 整轮响应超时
     * @param configuredProviders 本轮需要注入的工具提供器
     * @return 编排服务及依赖替身
     */
    @SuppressWarnings("unchecked")
    private TestContext context(
            Duration responseTimeout,
            ToolCallbackProvider... configuredProviders) {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        ConversationContextService contextService = mock(ConversationContextService.class);
        QuestionRewriteService questionRewriteService = mock(QuestionRewriteService.class);
        RoutedChatModelService model = mock(RoutedChatModelService.class);
        PurchaseActionService purchaseActionService = mock(PurchaseActionService.class);
        ActionDraftCreationTracker actionDraftCreationTracker = new ActionDraftCreationTracker();
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        // 工具提供器顺序保持与 Spring 容器一致，便于验证同名去重和最终白名单。
        when(providers.orderedStream()).thenReturn(Arrays.stream(configuredProviders));
        when(questionRewriteService.rewrite(any(), any())).thenAnswer(invocation -> {
            ConversationHistoryContext history = invocation.getArgument(0);
            return QuestionRewriteResult.unchanged(
                    history.currentQuestion().content(), false);
        });
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentChatPipeline pipeline = new AgentChatPipeline(
                memory,
                contextService,
                questionRewriteService,
                new QuestionToolRoutingService(),
                model,
                purchaseActionService,
                actionDraftCreationTracker,
                new McpToolContextFactory(),
                providers,
                Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));
        AgentChatService service = new AgentChatService(
                memory,
                pipeline,
                new AgentChatProperties(responseTimeout),
                new AgentChatMetrics(meterRegistry));
        return new TestContext(
                service, memory, contextService, questionRewriteService, model,
                purchaseActionService, actionDraftCreationTracker, meterRegistry);
    }

    /**
     * 创建仅提供稳定名称的工具回调替身。
     *
     * @param name 工具名称
     * @return 可参与编排白名单测试的工具回调
     */
    private ToolCallback toolCallback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        // 工具边界测试只依赖定义名称，不执行工具正文。
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
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
     * @param questionRewriteService 问题改写服务替身
     * @param model 回答模型替身
     * @param purchaseActionService 购票动作服务替身
     * @param actionDraftCreationTracker 本轮草案创建信号
     * @param meterRegistry 指标注册表
     */
    private record TestContext(
            AgentChatService service,
            ConversationMemoryService memory,
            ConversationContextService contextService,
            QuestionRewriteService questionRewriteService,
            RoutedChatModelService model,
            PurchaseActionService purchaseActionService,
            ActionDraftCreationTracker actionDraftCreationTracker,
            SimpleMeterRegistry meterRegistry) {
    }
}
