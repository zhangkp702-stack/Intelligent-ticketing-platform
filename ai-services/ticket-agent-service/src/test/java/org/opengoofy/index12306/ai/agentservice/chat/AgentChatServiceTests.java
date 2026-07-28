package org.opengoofy.index12306.ai.agentservice.chat;

import org.opengoofy.index12306.ai.agentservice.chat.service.AgentChatService;
import org.opengoofy.index12306.ai.agentservice.chat.exception.AgentChatException;
import org.opengoofy.index12306.ai.agentservice.chat.service.AgentChatMetrics;
import org.opengoofy.index12306.ai.agentservice.chat.service.AgentChatPipeline;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.action.service.PurchaseActionService;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.EventType;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentChatProperties;
import org.opengoofy.index12306.ai.agentservice.chat.rewrite.QuestionRewriteService;
import org.opengoofy.index12306.ai.agentservice.chat.rewrite.QuestionRewriteService.QuestionRewriteResult;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentClassificationService;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentClassificationService.CancellationIntentData;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentClassificationService.IntentClassificationResult;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentClassificationService.RefundIntentData;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentToolRoutingService;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.conversation.context.AgentChatMessage;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationHistoryContext;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationTurnContext;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationContextService;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.infra.model.observability.ModelHttpCallRound;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.RoutedChatModelService;
import org.opengoofy.index12306.ai.agentservice.workflow.service.PurchaseChainService;
import org.opengoofy.index12306.ai.agentservice.workflow.service.TicketOperationChainService;
import org.opengoofy.index12306.ai.agentservice.workflow.service.PurchaseWorkflowService;
import org.opengoofy.index12306.ai.agentservice.workflow.service.CancellationWorkflowService;
import org.opengoofy.index12306.ai.agentservice.workflow.service.RefundWorkflowService;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(test.model().stream(any(), any(), any(), eq(false), any())).thenAnswer(invocation -> {
            Consumer<ModelHttpCallRound> roundConsumer = invocation.getArgument(4);
            return Flux.defer(() -> {
                // 模拟底层过滤器在模型 HTTP 响应结束时回传单轮耗时。
                roundConsumer.accept(new ModelHttpCallRound(
                        1, "bailian", "primary", "qwen", "SUCCESS", 120, 480, 200));
                return Flux.just(firstResponse, secondResponse);
            });
        });

        StepVerifier.create(test.service().stream(command))
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.type()).isEqualTo(EventType.META))
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.delta()).isEqualTo("北京到"))
                .assertNext(event -> org.assertj.core.api.Assertions.assertThat(event.delta()).isEqualTo("上海有票"))
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.type()).isEqualTo(EventType.DONE);
                    org.assertj.core.api.Assertions.assertThat(event.content()).isEqualTo("北京到上海有票");
                    // 完成事件同时返回本轮性能快照，前端无需读取全局 Micrometer 聚合数据。
                    org.assertj.core.api.Assertions.assertThat(event.performance()).isNotNull();
                    org.assertj.core.api.Assertions.assertThat(event.performance().totalDurationMs()).isNotNegative();
                    org.assertj.core.api.Assertions.assertThat(event.performance().contextDurationMs()).isNotNegative();
                    org.assertj.core.api.Assertions.assertThat(event.performance().rewriteDurationMs()).isNotNegative();
                    org.assertj.core.api.Assertions.assertThat(event.performance().routingDurationMs()).isNotNegative();
                    org.assertj.core.api.Assertions.assertThat(event.performance().modelDurationMs()).isNotNegative();
                    org.assertj.core.api.Assertions.assertThat(event.performance().completionDurationMs()).isNotNegative();
                    org.assertj.core.api.Assertions.assertThat(event.performance().rewriteModelInvoked()).isFalse();
                    org.assertj.core.api.Assertions.assertThat(event.performance().rewritten()).isFalse();
                    org.assertj.core.api.Assertions.assertThat(event.performance().route()).isEqualTo("CHAT_ONLY");
                    org.assertj.core.api.Assertions.assertThat(event.performance().toolAvailability())
                            .isEqualTo("NOT_REQUIRED");
                    org.assertj.core.api.Assertions.assertThat(event.performance().enabledTools()).isEmpty();
                    org.assertj.core.api.Assertions.assertThat(event.performance().missingTools()).isEmpty();
                    org.assertj.core.api.Assertions.assertThat(event.performance().modelCalls())
                            .singleElement()
                            .satisfies(call -> {
                                org.assertj.core.api.Assertions.assertThat(call.round()).isEqualTo(1);
                                org.assertj.core.api.Assertions.assertThat(call.firstChunkMillis()).isEqualTo(120);
                                org.assertj.core.api.Assertions.assertThat(call.durationMillis()).isEqualTo(480);
                            });
                })
                .verifyComplete();
        verify(test.memory()).completeTurn(any());
        verify(test.contextService()).load(
                command.userId(), command.requestId(), command.conversationId(),
                "turn-1", "message-1", 1L, command.message());
        verify(test.purchaseActionService(), never()).confirmationForTurn(any(), any());

        // 捕获实际发送给模型的提示，确认独立只读工具可以在同一模型轮次中批量请求。
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model(), atLeastOnce()).stream(any(), promptCaptor.capture(), any(), eq(false), any());
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
        assertThat(test.meterRegistry()
                .get("agent.chat.context.duration").timer().count()).isEqualTo(1);
        assertThat(test.meterRegistry()
                .get("agent.chat.rewrite.duration").timer().count()).isEqualTo(1);
        assertThat(test.meterRegistry()
                .get("agent.chat.routing.duration").timer().count()).isEqualTo(1);
        assertThat(test.meterRegistry()
                .get("agent.chat.model.duration").timer().count()).isEqualTo(1);
        assertThat(test.meterRegistry()
                .get("agent.chat.completion.duration").timer().count()).isEqualTo(1);
    }

    /**
     * 验证上下文相关短问题使用改写后的独立问题调用回答模型。
     */
    @Test
    void contextualQuestionUsesRewrittenStandaloneQuestion() {
        ToolCallbackProvider provider = ToolCallbackProvider.from(
                toolCallback("resolve_station"),
                toolCallback("query_tickets"));
        TestContext test = context(Duration.ofSeconds(60), provider);
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
        when(test.intentClassificationService().classifyWithActionData(any(), any(), any(), any()))
                .thenReturn(classification(AgentIntent.TRAIN_QUERY));
        ChatResponse modelResponse = response("G9003 还有余票");
        when(test.model().stream(any(), any(), any(), eq(true), any()))
                .thenReturn(Flux.just(modelResponse));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        // 意图识别和回答模型都必须使用补全后的独立问题，避免再次丢失上文车次信息。
        verify(test.intentClassificationService()).classifyWithActionData(
                eq("明天北京到上海的第二趟车 G9003 还有票吗"),
                eq(conversationHistory),
                any(),
                any());
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model()).stream(any(), promptCaptor.capture(), any(), eq(true), any());
        assertThat(promptCaptor.getValue().getInstructions().get(
                promptCaptor.getValue().getInstructions().size() - 1))
                .isInstanceOfSatisfying(UserMessage.class, message ->
                        assertThat(message.getText())
                                .isEqualTo("明天北京到上海的第二趟车 G9003 还有票吗"));
    }

    /**
     * 验证固定购票链创建草案后直接读取数据库确认视图，不依赖进程内执行信号。
     */
    @Test
    void fixedPurchaseChainUsesPersistedDraftForAuthoritativeResult() {
        TestContext test = context();
        ChatCommand command = new ChatCommand(
                "request-purchase", "request-purchase", "user-1", "tester",
                "conversation-1", "帮万重山购买 G9001 次列车一等座");
        ActionConfirmationView action = new ActionConfirmationView(
                "action-1", "TICKET_PURCHASE", AgentActionStatus.AWAITING_CONFIRMATION,
                "购买 G9004 次列车", Instant.parse("2026-07-16T00:10:00Z"), "confirmation-token");

        // 固定链返回草案结果，数据库中保存对应待确认操作。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, history(command.conversationId(), command.message(), List.of()));
        when(test.intentClassificationService().classifyWithActionData(any(), any(), any(), any()))
                .thenReturn(classification(AgentIntent.TICKET_PURCHASE));
        when(test.purchaseChainService().execute(any(), any())).thenReturn(
                new PurchaseChainService.PurchaseChainResult("已生成购票草案"));
        when(test.purchaseActionService().confirmationForTurn(command.userId(), "turn-1"))
                .thenReturn(Optional.of(action));

        // 最终正文和确认按钮必须来自数据库状态，购票链不能调用回答模型。
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
                })
                .verifyComplete();

        ArgumentCaptor<ConversationMemoryService.CompleteTurnCommand> completionCaptor =
                ArgumentCaptor.forClass(ConversationMemoryService.CompleteTurnCommand.class);
        verify(test.memory()).completeTurn(completionCaptor.capture());
        assertThat(completionCaptor.getValue().content()).contains("尚未创建订单");
        verify(test.purchaseActionService()).confirmationForTurn(command.userId(), "turn-1");
        verify(test.model(), never()).stream(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 验证服务端已经查到乘车人时，模型不能用相反的自然语言覆盖真实查询结果。
     */
    @Test
    void verifiedPassengersOverrideModelAbsenceClaim() {
        TestContext test = context();
        ChatCommand command = new ChatCommand(
                "request-verified-passenger", "request-verified-passenger", "user-1", "tester",
                "conversation-1", "帮万重山买明天早上七点的票");

        // 模拟工具已经返回两名有效乘车人，但模型仍错误生成空乘车人结论。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, history(command.conversationId(), command.message(), List.of()));
        when(test.intentClassificationService().classifyWithActionData(any(), any(), any(), any()))
                .thenReturn(classification(AgentIntent.TICKET_PURCHASE));
        ChatResponse incorrectModelResponse = response("系统提示当前账号没有可用乘车人");
        when(test.model().stream(any(), any(), any(), eq(true), any()))
                .thenReturn(Flux.just(incorrectModelResponse));
        // 购票意图不再调用回答模型；字段不足由确定性链路直接返回。
        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo(EventType.DONE);
                    assertThat(event.content()).contains("购票信息不完整");
                    assertThat(event.content()).doesNotContain("没有可用乘车人");
                })
                .verifyComplete();
        verify(test.model(), never()).stream(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 验证取消订单意图直接进入固定代码链，不会调用回答模型选择工具。
     */
    @Test
    void cancellationIntentUsesFixedCodeChain() {
        TestContext test = context();
        ChatCommand command = new ChatCommand(
                "request-cancel", "request-cancel", "user-1", "tester",
                "conversation-1", "取消明天 G9001 的订单");
        CancellationIntentData cancellationRequest =
                new CancellationIntentData(null, "G9001", "2026-07-28");

        // 意图模型只输出取消字段，后续订单定位和草案创建由代码链完成。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, history(command.conversationId(), command.message(), List.of()));
        when(test.intentClassificationService().classifyWithActionData(any(), any(), any(), any()))
                .thenReturn(new IntentClassificationResult(
                        AgentIntent.ORDER_CANCELLATION, null, cancellationRequest, null));
        when(test.ticketOperationChainService().executeCancellation(any(), eq(cancellationRequest)))
                .thenReturn(new TicketOperationChainService.OperationChainResult("取消订单草案已生成。"));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        verify(test.ticketOperationChainService()).executeCancellation(any(), eq(cancellationRequest));
        verify(test.model(), never()).stream(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 验证固定取消链完成后会从数据库服务恢复待选择订单，并发送工作流事件。
     */
    @Test
    void cancellationChainRestoresPendingWorkflowFromDatabase() {
        TestContext test = context();
        ChatCommand command = new ChatCommand(
                "request-cancel-workflow", "request-cancel-workflow", "user-1", "tester",
                "conversation-1", "取消明天的订单");
        CancellationIntentData cancellationRequest =
                new CancellationIntentData(null, null, "2026-07-28");
        OrderSelectionView workflow = new OrderSelectionView(
                "workflow-1", WorkflowStage.SELECTING_ORDER, "请选择需要取消的订单", List.of());

        // 固定链先建立数据库工作流，完成阶段随后从对应服务恢复同一待选择状态。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, history(command.conversationId(), command.message(), List.of()));
        when(test.intentClassificationService().classifyWithActionData(any(), any(), any(), any()))
                .thenReturn(new IntentClassificationResult(
                        AgentIntent.ORDER_CANCELLATION, null, cancellationRequest, null));
        when(test.ticketOperationChainService().executeCancellation(any(), eq(cancellationRequest)))
                .thenReturn(new TicketOperationChainService.OperationChainResult("等待用户选择订单。"));
        when(test.cancellationWorkflowService().findPendingSelection("user-1", "conversation-1"))
                .thenReturn(Optional.of(workflow));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo(EventType.WORKFLOW_REQUIRED);
                    assertThat(event.workflow()).isSameAs(workflow);
                })
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo(EventType.DONE);
                    assertThat(event.content()).contains(workflow.prompt());
                })
                .verifyComplete();

        verify(test.cancellationWorkflowService())
                .findPendingSelection("user-1", "conversation-1");
        verify(test.model(), never()).stream(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 验证退票意图直接进入固定代码链，不会调用回答模型补充业务流程。
     */
    @Test
    void refundIntentUsesFixedCodeChain() {
        TestContext test = context();
        ChatCommand command = new ChatCommand(
                "request-refund", "request-refund", "user-1", "tester",
                "conversation-1", "给万重山退掉明天 G9001 的票");
        RefundIntentData refundRequest =
                new RefundIntentData(null, "G9001", "2026-07-28", List.of("万重山"));

        // 意图模型提供订单定位和乘车人字段，退票查询、预览及草案顺序由代码固定。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, history(command.conversationId(), command.message(), List.of()));
        when(test.intentClassificationService().classifyWithActionData(any(), any(), any(), any()))
                .thenReturn(new IntentClassificationResult(
                        AgentIntent.TICKET_REFUND, null, null, refundRequest));
        when(test.ticketOperationChainService().executeRefund(any(), eq(refundRequest)))
                .thenReturn(new TicketOperationChainService.OperationChainResult("退票草案已生成。"));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        verify(test.ticketOperationChainService()).executeRefund(any(), eq(refundRequest));
        verify(test.model(), never()).stream(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 验证固定购票链不会读取或校验回答模型工具提供器。
     */
    @Test
    void purchaseCodeChainDoesNotResolveModelTools() {
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        TestContext test = context(Duration.ofSeconds(60), provider);
        ChatCommand command = new ChatCommand(
                "request-purchase", "request-purchase", "user-1", "tester",
                "conversation-1", "帮我购买明天北京到上海的二等座车票");
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(), command.message(), List.of());
        // 模拟一次购票业务问答，固定链在工具提供器解析之前直接执行。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
        when(test.intentClassificationService().classifyWithActionData(any(), any(), any(), any()))
                .thenReturn(classification(AgentIntent.TICKET_PURCHASE));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        // 固定链既不调用回答模型，也不读取回答模型工具提供器。
        verify(provider, never()).getToolCallbacks();
        verify(test.model(), never()).stream(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 验证乘车人查询分流要求的只读工具能够通过回答模型最终白名单。
     */
    @Test
    void passengerQueryReceivesPassengerTool() {
        ToolCallback passengerTool = toolCallback("list_my_passengers");
        ToolCallback passengerLookupTool = toolCallback("find_my_passengers_by_name");
        ToolCallbackProvider provider = ToolCallbackProvider.from(passengerTool, passengerLookupTool);
        TestContext test = context(Duration.ofSeconds(60), provider);
        ChatCommand command = new ChatCommand(
                "request-passenger", "request-passenger", "user-1", "tester",
                "conversation-1", "查询有没有万重山这个乘车人");
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(), command.message(), List.of());
        ChatResponse modelResponse = response("已查询当前账号乘车人");

        // 模拟乘车人查询意图，确保分流工具不会被流水线的最终白名单再次移除。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
        when(test.intentClassificationService().classifyWithActionData(any(), any(), any(), any()))
                .thenReturn(classification(AgentIntent.PASSENGER_QUERY));
        when(test.model().stream(any(), any(), any(), eq(true), any())).thenReturn(Flux.just(modelResponse));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        // 捕获最终模型选项，确认乘车人查询工具已实际注册且没有缺失工具。
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model()).stream(any(), promptCaptor.capture(), any(), eq(true), any());
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("list_my_passengers", "find_my_passengers_by_name");
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
        when(test.model().stream(any(), any(), any(), eq(false), any()))
                .thenReturn(Flux.just(modelResponse));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model()).stream(any(), promptCaptor.capture(), any(), eq(false), any());
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks()).isEmpty();
    }

    /**
     * 验证业务路径缺少必需工具时不会继续调用回答模型。
     */
    @Test
    void missingBusinessToolsStopsBeforeModelCall() {
        TestContext test = context();
        ChatCommand command = new ChatCommand(
                "request-missing-tools", "request-missing-tools", "user-1", "tester",
                "conversation-1", "查询明天北京到上海的余票");
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(), command.message(), List.of());

        // 模拟业务问题已经完成上下文加载，但当前没有任何 MCP 工具提供器。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);

        // 意图模型已识别为查票，后续缺少工具时应在回答模型调用前失败。
        when(test.intentClassificationService().classifyWithActionData(any(), any(), any(), any()))
                .thenReturn(classification(AgentIntent.TRAIN_QUERY));

        StepVerifier.create(test.service().stream(command))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOfSatisfying(AgentChatException.class, exception -> {
                            assertThat(exception.failureCategory()).isEqualTo("MCP_TOOLS_UNAVAILABLE");
                            assertThat(exception.getMessage()).contains("工具暂时不可用");
                        }))
                .verify();

        verify(test.model(), never()).stream(any(), any(), any(), anyBoolean(), any());
        verify(test.memory()).failTurn(eq(command.userId()), eq("turn-1"), any());
        assertThat(test.meterRegistry()
                .get("agent.chat.tools.missing")
                .tag("tool", "resolve_station")
                .counter().count()).isEqualTo(1);
        assertThat(test.meterRegistry()
                .get("agent.chat.routing.requests")
                .tag("route", "TOOL_ASSISTED")
                .tag("toolAvailability", "MISSING")
                .counter().count()).isEqualTo(1);
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
        verify(test.model(), never()).stream(any(), any(), any(), any(Boolean.class), any());
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
        when(test.model().stream(any(), any(), any(), eq(false), any())).thenReturn(Flux.never());

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
                        .isInstanceOf(AgentChatException.class))
                .verify(Duration.ofSeconds(1));
        verify(test.memory()).cancelTurn(command.userId(), "turn-1");
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
        IntentClassificationService intentClassificationService = mock(IntentClassificationService.class);
        RoutedChatModelService model = mock(RoutedChatModelService.class);
        PurchaseActionService purchaseActionService = mock(PurchaseActionService.class);
        PurchaseWorkflowService purchaseWorkflowService = mock(PurchaseWorkflowService.class);
        CancellationWorkflowService cancellationWorkflowService = mock(CancellationWorkflowService.class);
        RefundWorkflowService refundWorkflowService = mock(RefundWorkflowService.class);
        PurchaseChainService purchaseChainService = mock(PurchaseChainService.class);
        TicketOperationChainService ticketOperationChainService = mock(TicketOperationChainService.class);
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        // 工具提供器顺序保持与 Spring 容器一致，便于验证同名去重和最终白名单。
        when(providers.orderedStream()).thenReturn(Arrays.stream(configuredProviders));
        when(questionRewriteService.rewrite(any(), any())).thenAnswer(invocation -> {
            ConversationHistoryContext history = invocation.getArgument(0);
            return QuestionRewriteResult.unchanged(
                    history.currentQuestion().content(), false);
        });
        when(intentClassificationService.classifyWithActionData(any(), any(), any(), any()))
                .thenReturn(classification(AgentIntent.GENERAL_CHAT));
        // 默认让购票测试落入确定性代码链路，普通问答仍覆盖原有模型路径。
        when(purchaseChainService.execute(any(), any())).thenReturn(
                new PurchaseChainService.PurchaseChainResult("购票信息不完整：缺少乘车人。"));
        when(ticketOperationChainService.executeCancellation(any(), any())).thenReturn(
                new TicketOperationChainService.OperationChainResult("请选择需要取消的订单。"));
        when(ticketOperationChainService.executeRefund(any(), any())).thenReturn(
                new TicketOperationChainService.OperationChainResult("请选择需要退票的订单。"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentChatMetrics chatMetrics = new AgentChatMetrics(meterRegistry);
        AgentChatPipeline pipeline = new AgentChatPipeline(
                memory,
                contextService,
                questionRewriteService,
                intentClassificationService,
                new IntentToolRoutingService(),
                chatMetrics,
                model,
                purchaseActionService,
                purchaseWorkflowService,
                cancellationWorkflowService,
                refundWorkflowService,
                purchaseChainService,
                ticketOperationChainService,
                new McpToolContextFactory(),
                providers,
                Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));
        AgentChatService service = new AgentChatService(
                memory,
                pipeline,
                new AgentChatProperties(responseTimeout),
                chatMetrics);
        return new TestContext(
                service, memory, contextService, questionRewriteService, intentClassificationService, model,
                purchaseActionService, purchaseWorkflowService,
                cancellationWorkflowService, purchaseChainService, ticketOperationChainService, meterRegistry);
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
     * 为编排测试构造不携带购票字段的受控意图识别结果。
     *
     * @param intent 测试需要的业务意图
     * @return 与生产分类接口一致的结构化结果
     */
    private IntentClassificationResult classification(AgentIntent intent) {
        return new IntentClassificationResult(intent, null, null, null);
    }

    /**
     * 创建稳定的测试对话命令。
     *
     * @return 测试命令
     */
    private ChatCommand command() {
        return new ChatCommand(
                "request-1", "request-1", "user-1", "tester",
                "conversation-1", "你好，请介绍一下你自己");
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
     * @param intentClassificationService 意图分类模型服务替身
     * @param model 回答模型替身
     * @param purchaseActionService 购票动作服务替身
     * @param purchaseWorkflowService 购票工作流服务替身
     * @param meterRegistry 指标注册表
     */
    private record TestContext(
            AgentChatService service,
            ConversationMemoryService memory,
            ConversationContextService contextService,
            QuestionRewriteService questionRewriteService,
            IntentClassificationService intentClassificationService,
            RoutedChatModelService model,
            PurchaseActionService purchaseActionService,
            PurchaseWorkflowService purchaseWorkflowService,
            CancellationWorkflowService cancellationWorkflowService,
            PurchaseChainService purchaseChainService,
            TicketOperationChainService ticketOperationChainService,
            SimpleMeterRegistry meterRegistry) {
    }
}
