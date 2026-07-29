package org.opengoofy.index12306.ai.agentservice.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.chat.service.AgentChatService;
import org.opengoofy.index12306.ai.agentservice.chat.service.impl.AgentChatServiceImpl;
import org.opengoofy.index12306.ai.agentservice.chat.exception.AgentChatException;
import org.opengoofy.index12306.ai.agentservice.chat.execution.AgentChatPipeline;
import org.opengoofy.index12306.ai.agentservice.chat.observability.AgentChatMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.action.service.PurchaseActionService;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.EventType;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentChatProperties;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.execution.ReadTaskChain;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskDependencyResolver;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskPlanExecutor;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskPlan;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskSlots;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TrainSelectionPolicy;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.WorkflowRelation;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanner;
import org.opengoofy.index12306.ai.agentservice.chat.model.IntentActionModels.CancellationIntentData;
import org.opengoofy.index12306.ai.agentservice.chat.model.IntentActionModels.PurchaseIntentData;
import org.opengoofy.index12306.ai.agentservice.chat.model.IntentActionModels.RefundIntentData;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentExecutionRouter;
import org.opengoofy.index12306.ai.agentservice.conversation.context.AgentChatMessage;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationHistoryContext;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationTurnContext;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationContextLoader;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.infra.model.observability.ModelHttpCallRound;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.RoutedChatModelService;
import org.opengoofy.index12306.ai.agentservice.workflow.execution.PurchaseChainExecutor;
import org.opengoofy.index12306.ai.agentservice.workflow.execution.TicketOperationChainExecutor;
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
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
                    org.assertj.core.api.Assertions.assertThat(event.performance().rewriteModelInvoked()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(event.performance().rewritten()).isFalse();
                    org.assertj.core.api.Assertions.assertThat(event.performance().route()).isEqualTo("CHAT_ONLY");
                    org.assertj.core.api.Assertions.assertThat(event.performance().toolAvailability())
                            .isEqualTo("DIRECT_CHAIN");
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
        assertThat(promptCaptor.getValue().getOptions()).isNull();
        assertThat(promptCaptor.getValue().getInstructions())
                .filteredOn(message -> message instanceof UserMessage)
                .extracting(message -> message.getText())
                .singleElement()
                .satisfies(text -> assertThat(text)
                        .contains("\"originalQuestion\":")
                        .contains(command.message())
                        .contains("\"intent\":\"GENERAL_CHAT\""));

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

        // 任务规划阶段把省略问句补全，固定链使用补全后的独立问题。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
        doReturn(plan(task(
                        AgentIntent.TRAIN_QUERY,
                        command.message(),
                        "明天北京到上海的第二趟车 G9003 还有票吗",
                        new TaskSlots(
                                "北京", "上海", "2026-07-17", "G9003",
                                null, null, null, List.of(), null, null))))
                .when(test.taskPlanner()).plan(eq(conversationHistory), any(), any());
        ChatResponse modelResponse = response("G9003 还有余票");
        when(test.model().stream(any(), any(), any(), eq(false), any()))
                .thenReturn(Flux.just(modelResponse));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        // 固定链必须使用补全后的独立问题，最终回答模型只接收固定链结果且没有工具。
        verify(test.readTaskChain()).execute(
                any(),
                org.mockito.ArgumentMatchers.argThat(task ->
                        "明天北京到上海的第二趟车 G9003 还有票吗".equals(task.standaloneQuestion())),
                any());
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model()).stream(any(), promptCaptor.capture(), any(), eq(false), any());
        assertThat(promptCaptor.getValue().getOptions()).isNull();
        assertThat(promptCaptor.getValue().getInstructions())
                .extracting(message -> message.getText())
                .anyMatch(text -> text.contains("明天北京到上海的第二趟车 G9003 还有票吗"));
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
        doReturn(plan(task(
                        AgentIntent.TICKET_PURCHASE,
                        command.message(),
                        command.message(),
                        new TaskSlots(
                                null, null, null, "G9001", null,
                                "一等座", null, List.of("万重山"), null, null))))
                .when(test.taskPlanner()).plan(any(), any(), any());
        when(test.purchaseChainExecutor().execute(any(), any())).thenReturn(
                new PurchaseChainExecutor.PurchaseChainResult("已生成购票草案"));
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
        doReturn(plan(task(
                        AgentIntent.TICKET_PURCHASE,
                        command.message(),
                        command.message(),
                        new TaskSlots(
                                null, null, "2026-07-17", null, "07:00",
                                null, null, List.of("万重山"), null, null))))
                .when(test.taskPlanner()).plan(any(), any(), any());
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
        doReturn(plan(task(
                        AgentIntent.ORDER_CANCELLATION,
                        command.message(),
                        command.message(),
                        new TaskSlots(
                                null, null, null, cancellationRequest.trainNumber(),
                                null, null, null, List.of(), cancellationRequest.orderSn(),
                                cancellationRequest.ridingDate()))))
                .when(test.taskPlanner()).plan(any(), any(), any());
        when(test.ticketOperationChainExecutor().executeCancellation(any(), eq(cancellationRequest)))
                .thenReturn(new TicketOperationChainExecutor.OperationChainResult("取消订单草案已生成。"));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        verify(test.ticketOperationChainExecutor()).executeCancellation(any(), eq(cancellationRequest));
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
        doReturn(plan(task(
                        AgentIntent.ORDER_CANCELLATION,
                        command.message(),
                        command.message(),
                        new TaskSlots(
                                null, null, null, cancellationRequest.trainNumber(),
                                null, null, null, List.of(), cancellationRequest.orderSn(),
                                cancellationRequest.ridingDate()))))
                .when(test.taskPlanner()).plan(any(), any(), any());
        when(test.ticketOperationChainExecutor().executeCancellation(any(), eq(cancellationRequest)))
                .thenReturn(new TicketOperationChainExecutor.OperationChainResult("等待用户选择订单。"));
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
        doReturn(plan(task(
                        AgentIntent.TICKET_REFUND,
                        command.message(),
                        command.message(),
                        new TaskSlots(
                                null, null, null, refundRequest.trainNumber(),
                                null, null, null, refundRequest.passengerNames(),
                                refundRequest.orderSn(), refundRequest.ridingDate()))))
                .when(test.taskPlanner()).plan(any(), any(), any());
        when(test.ticketOperationChainExecutor().executeRefund(any(), eq(refundRequest)))
                .thenReturn(new TicketOperationChainExecutor.OperationChainResult("退票草案已生成。"));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        verify(test.ticketOperationChainExecutor()).executeRefund(any(), eq(refundRequest));
        verify(test.model(), never()).stream(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 验证固定购票链不会读取或校验回答模型工具提供器。
     */
    @Test
    void purchaseCodeChainDoesNotResolveModelTools() {
        TestContext test = context();
        ChatCommand command = new ChatCommand(
                "request-purchase", "request-purchase", "user-1", "tester",
                "conversation-1", "帮我购买明天北京到上海的二等座车票");
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(), command.message(), List.of());
        // 模拟一次购票业务问答，固定链在工具提供器解析之前直接执行。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
        doReturn(plan(task(
                        AgentIntent.TICKET_PURCHASE,
                        command.message(),
                        command.message(),
                        new TaskSlots(
                                "北京", "上海", "2026-07-17", null,
                                null, "二等座", null, List.of(), null, null))))
                .when(test.taskPlanner()).plan(any(), any(), any());

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        // 固定链既不调用回答模型，也不把任何工具提供给回答模型。
        verify(test.model(), never()).stream(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * 验证乘车人查询由固定链执行，回答模型只接收执行结果。
     */
    @Test
    void passengerQueryReceivesPassengerTool() {
        TestContext test = context();
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
        doReturn(plan(task(
                        AgentIntent.PASSENGER_QUERY,
                        command.message(),
                        command.message(),
                        new TaskSlots(
                                null, null, null, null, null, null, null,
                                List.of("万重山"), null, null))))
                .when(test.taskPlanner()).plan(any(), any(), any());
        when(test.model().stream(any(), any(), any(), eq(false), any())).thenReturn(Flux.just(modelResponse));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        // 乘车人查询由固定链直接执行，最终模型调用明确关闭工具。
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.readTaskChain()).execute(any(), any(), any());
        verify(test.model()).stream(any(), promptCaptor.capture(), any(), eq(false), any());
        assertThat(promptCaptor.getValue().getOptions()).isNull();
    }

    /**
     * 验证普通问答即使存在工具提供器也不会向回答模型注册 MCP 工具。
     */
    @Test
    void ordinaryQuestionSkipsMcpTools() {
        TestContext test = context();
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
        assertThat(promptCaptor.getValue().getOptions()).isNull();
    }

    /**
     * 验证业务路径缺少必需工具时不会继续调用回答模型。
     */
    @Test
    void failedReadTaskStillProducesSafeSummary() {
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

        // 规划结果进入查票固定链，单个查询异常由调度器转换为安全失败结果。
        doReturn(plan(task(
                        AgentIntent.TRAIN_QUERY,
                        command.message(),
                        command.message(),
                        new TaskSlots(
                                "北京", "上海", "2026-07-17", null,
                                null, null, null, List.of(), null, null))))
                .when(test.taskPlanner()).plan(any(), any(), any());
        doReturn(reactor.core.publisher.Mono.error(
                new IllegalStateException("resolve_station unavailable")))
                .when(test.readTaskChain()).execute(any(), any(), any());
        ChatResponse failureSummary = response("车票查询暂时失败，请稍后重试");
        when(test.model().stream(any(), any(), any(), eq(false), any()))
                .thenReturn(Flux.just(failureSummary));

        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectNextMatches(event -> event.type() == EventType.DELTA)
                .expectNextMatches(event -> event.type() == EventType.DONE)
                .verifyComplete();

        verify(test.model()).stream(any(), any(), any(), eq(false), any());
        verify(test.memory()).completeTurn(any());
    }

    /**
     * 验证复合问题只规划一次、并行执行查询、串行进入交易链并进行一次无工具汇总。
     */
    @Test
    void compoundRequestExecutesFixedChainsAndUsesToolFreeSummary() {
        TestContext test = context();
        ChatCommand command = new ChatCommand(
                "request-compound", "request-compound", "user-1", "tester",
                "conversation-1",
                "查询北京到南京的车票，买最早的一等座，再查询当前乘车人");
        ConversationHistoryContext conversationHistory = history(
                command.conversationId(), command.message(), List.of());
        PlannedTask trainTask = new PlannedTask(
                "task-1", 1, AgentIntent.TRAIN_QUERY,
                "查询北京到南京的车票", "查询 2026-07-17 北京到南京的车票",
                new TaskSlots(
                        "北京", "南京", "2026-07-17", null,
                        null, null, null, List.of(), null, null),
                List.of(), List.of(), WorkflowRelation.INDEPENDENT, List.of());
        PlannedTask purchaseTask = new PlannedTask(
                "task-2", 2, AgentIntent.TICKET_PURCHASE,
                "买最早的一等座", "购买上述查询结果中最早的一班一等座",
                new TaskSlots(
                        "北京", "南京", "2026-07-17", null,
                        null, "一等座", TrainSelectionPolicy.EARLIEST,
                        List.of("万重山"), null, null),
                List.of(), List.of("task-1"), WorkflowRelation.INDEPENDENT, List.of());
        PlannedTask passengerTask = new PlannedTask(
                "task-3", 3, AgentIntent.PASSENGER_QUERY,
                "查询当前乘车人", "查询当前账号下的乘车人",
                emptySlots(),
                List.of(), List.of(), WorkflowRelation.INDEPENDENT, List.of());

        // 规划结果一次返回三个任务，两个查询由固定链处理，购票继续走服务端代码链。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        stubHistory(test, command, conversationHistory);
        doReturn(new TaskPlan(List.of(trainTask, purchaseTask, passengerTask)))
                .when(test.taskPlanner()).plan(eq(conversationHistory), any(), any());
        doReturn(reactor.core.publisher.Mono.just(new TaskExecutionResult(
                "task-1", 1, AgentIntent.TRAIN_QUERY, TaskExecutionStatus.SUCCESS,
                trainTask.standaloneQuestion(),
                "{\"trains\":[{\"trainId\":\"train-1\",\"trainNumber\":\"G1\","
                        + "\"departureTime\":\"06:30\",\"seats\":[{\"type\":1,\"quantity\":3,\"price\":320}]}]}",
                List.of(), null, null)))
                .when(test.readTaskChain()).execute(
                        any(), org.mockito.ArgumentMatchers.argThat(task -> task.sequence() == 1), any());
        doReturn(reactor.core.publisher.Mono.just(new TaskExecutionResult(
                "task-3", 3, AgentIntent.PASSENGER_QUERY, TaskExecutionStatus.SUCCESS,
                passengerTask.standaloneQuestion(), "[{\"realName\":\"万重山\"}]",
                List.of(), null, null)))
                .when(test.readTaskChain()).execute(
                        any(), org.mockito.ArgumentMatchers.argThat(task -> task.sequence() == 3), any());
        when(test.purchaseChainExecutor().execute(any(), any())).thenReturn(
                new PurchaseChainExecutor.PurchaseChainResult("已返回可购买车次，请选择具体车次。"));
        ChatResponse summaryResponse = response("已完成车票和乘车人查询，购票仍需选择具体车次。");
        when(test.model().stream(any(), any(), any(), eq(false), any()))
                .thenReturn(Flux.just(summaryResponse));

        StepVerifier.create(test.service().stream(command).collectList())
                .assertNext(events -> {
                    assertThat(events).extracting(event -> event.type())
                            .contains(EventType.META, EventType.DELTA, EventType.DONE);
                    assertThat(events.stream()
                            .filter(event -> event.type() == EventType.DONE)
                            .findFirst()
                            .orElseThrow()
                            .performance()
                            .route()).isEqualTo("MULTI_TASK_DIRECT_CHAIN");
                })
                .verifyComplete();

        verify(test.taskPlanner()).plan(eq(conversationHistory), any(), any());
        verify(test.readTaskChain(), org.mockito.Mockito.times(2)).execute(any(), any(), any());
        ArgumentCaptor<PurchaseIntentData> purchaseCaptor =
                ArgumentCaptor.forClass(PurchaseIntentData.class);
        verify(test.purchaseChainExecutor()).execute(any(), purchaseCaptor.capture());
        assertThat(purchaseCaptor.getValue().trainNumber()).isEqualTo("G1");
        assertThat(purchaseCaptor.getValue().departureTime()).isEqualTo("06:30");
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(test.model()).stream(any(), promptCaptor.capture(), any(), eq(false), any());
        assertThat(promptCaptor.getValue().getOptions()).isNull();
        assertThat(promptCaptor.getValue().getInstructions())
                .extracting(message -> message.getText())
                .anySatisfy(text -> assertThat(text)
                        .contains("\\\"trainNumber\\\":\\\"G1\\\"")
                        .contains("\\\"realName\\\":")
                        .contains("\"intent\":\"TICKET_PURCHASE\"")
                        .contains("\"result\":null")
                        .contains("\"authoritativeContentAppendedSeparately\":true"));
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
    private TestContext context(Duration responseTimeout) {
        // 测试上下文只注入固定业务链和最终回答模型，不再创建模型工具提供器。
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        ConversationContextLoader contextService = mock(ConversationContextLoader.class);
        TaskPlanner taskPlanner = mock(TaskPlanner.class);
        ReadTaskChain readTaskChain = mock(ReadTaskChain.class);
        RoutedChatModelService model = mock(RoutedChatModelService.class);
        PurchaseActionService purchaseActionService = mock(PurchaseActionService.class);
        PurchaseWorkflowService purchaseWorkflowService = mock(PurchaseWorkflowService.class);
        CancellationWorkflowService cancellationWorkflowService = mock(CancellationWorkflowService.class);
        RefundWorkflowService refundWorkflowService = mock(RefundWorkflowService.class);
        PurchaseChainExecutor purchaseChainExecutor = mock(PurchaseChainExecutor.class);
        TicketOperationChainExecutor ticketOperationChainExecutor = mock(TicketOperationChainExecutor.class);
        // 默认规划单个普通问答任务，具体业务场景在各测试中覆盖该计划。
        when(taskPlanner.plan(any(), any(), any())).thenAnswer(invocation -> {
            ConversationHistoryContext history = invocation.getArgument(0);
            return plan(task(
                    AgentIntent.GENERAL_CHAT,
                    history.currentQuestion().content(),
                    history.currentQuestion().content(),
                    emptySlots()));
        });
        when(readTaskChain.execute(any(), any(), any())).thenAnswer(invocation -> {
            PlannedTask task = invocation.getArgument(1);
            return reactor.core.publisher.Mono.just(new TaskExecutionResult(
                    task.taskId(),
                    task.sequence(),
                    task.intent(),
                    TaskExecutionStatus.SUCCESS,
                    task.standaloneQuestion(),
                    "{\"result\":\"ok\"}",
                    List.of(),
                    null,
                    null));
        });
        ChatResponse defaultModelResponse = response("默认汇总回答");
        when(model.stream(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(Flux.just(defaultModelResponse));
        // 默认让购票测试落入确定性代码链路，普通问答仍覆盖原有模型路径。
        when(purchaseChainExecutor.execute(any(), any())).thenReturn(
                new PurchaseChainExecutor.PurchaseChainResult("购票信息不完整：缺少乘车人。"));
        when(ticketOperationChainExecutor.executeCancellation(any(), any())).thenReturn(
                new TicketOperationChainExecutor.OperationChainResult("请选择需要取消的订单。"));
        when(ticketOperationChainExecutor.executeRefund(any(), any())).thenReturn(
                new TicketOperationChainExecutor.OperationChainResult("请选择需要退票的订单。"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentChatMetrics chatMetrics = new AgentChatMetrics(meterRegistry);
        AgentChatProperties chatProperties = new AgentChatProperties(
                responseTimeout,
                Duration.ofSeconds(5),
                4);
        AgentChatPipeline pipeline = new AgentChatPipeline(
                memory,
                contextService,
                taskPlanner,
                new TaskPlanExecutor(chatProperties, chatMetrics),
                readTaskChain,
                new TaskDependencyResolver(new ObjectMapper()),
                new IntentExecutionRouter(),
                chatMetrics,
                model,
                purchaseActionService,
                purchaseWorkflowService,
                cancellationWorkflowService,
                refundWorkflowService,
                purchaseChainExecutor,
                ticketOperationChainExecutor,
                new ObjectMapper());
        AgentChatService service = new AgentChatServiceImpl(
                memory,
                pipeline,
                chatProperties,
                chatMetrics);
        return new TestContext(
                service, memory, contextService, taskPlanner, readTaskChain, model,
                purchaseActionService, purchaseWorkflowService,
                cancellationWorkflowService, purchaseChainExecutor, ticketOperationChainExecutor, meterRegistry);
    }

    /**
     * 创建只包含一个任务的测试计划。
     *
     * @param task 当前测试任务
     * @return 单任务计划
     */
    private TaskPlan plan(PlannedTask task) {
        // 流水线测试只关心执行集成，规划模型结构校验由独立测试覆盖。
        return new TaskPlan(List.of(task));
    }

    /**
     * 创建稳定的单个测试任务。
     *
     * @param intent 当前意图
     * @param originalClause 用户原文
     * @param standaloneQuestion 补全后的独立问题
     * @param slots 当前业务槽位
     * @return 可直接交给调度器的任务
     */
    private PlannedTask task(
            AgentIntent intent,
            String originalClause,
            String standaloneQuestion,
            TaskSlots slots) {
        // 单任务默认不依赖其他任务，也不延续活动工作流。
        return new PlannedTask(
                "task-1",
                1,
                intent,
                originalClause,
                standaloneQuestion,
                slots,
                List.of(),
                List.of(),
                WorkflowRelation.INDEPENDENT,
                List.of());
    }

    /**
     * 创建所有字段为空的统一槽位对象。
     *
     * @return 空业务槽位
     */
    private TaskSlots emptySlots() {
        return new TaskSlots(
                null, null, null, null, null, null, null, List.of(), null, null);
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
     * @param taskPlanner 多任务规划服务替身
     * @param readTaskChain 只读固定链替身
     * @param model 回答模型替身
     * @param purchaseActionService 购票动作服务替身
     * @param purchaseWorkflowService 购票工作流服务替身
     * @param meterRegistry 指标注册表
     */
    private record TestContext(
            AgentChatService service,
            ConversationMemoryService memory,
            ConversationContextLoader contextService,
            TaskPlanner taskPlanner,
            ReadTaskChain readTaskChain,
            RoutedChatModelService model,
            PurchaseActionService purchaseActionService,
            PurchaseWorkflowService purchaseWorkflowService,
            CancellationWorkflowService cancellationWorkflowService,
            PurchaseChainExecutor purchaseChainExecutor,
            TicketOperationChainExecutor ticketOperationChainExecutor,
            SimpleMeterRegistry meterRegistry) {
    }
}
