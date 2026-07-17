package org.opengoofy.index12306.ai.agentservice.chat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionService;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.EventType;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentChatProperties;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageType;
import org.opengoofy.index12306.ai.agentservice.memory.domain.RouteDecision;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TurnStatus;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.memory.service.TopicContextService;
import org.opengoofy.index12306.ai.agentservice.memory.service.TopicRoutingService;
import org.opengoofy.index12306.ai.agentservice.model.routing.RoutedChatModelService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话编排正常完成和幂等复用行为测试。
 */
class AgentChatServiceTests {

    /**
     * 验证新问题完成主题路由、流式回答和轮次持久化闭环。
     */
    @Test
    void newTurnRoutesStreamsAndCompletes() {
        TestContext test = context();
        ChatCommand command = command();
        AgentRequestContext routedContext = new AgentRequestContext(
                command.requestId(), command.userId(), command.username(),
                command.conversationId(), "turn-1", "topic-1");
        TopicContextService.TopicContext topicContext = new TopicContextService.TopicContext(
                command.conversationId(), "topic-1", null, null, null,
                List.of(new TopicContextService.ContextMessage(
                        "message-1", 1L, MessageRole.USER, MessageType.TEXT, command.message(), 3)),
                3);

        // 模拟新轮次和已确定主题的上下文，回答模型返回两个流式增量。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        when(test.topicRouting().route(any(), eq("message-1"))).thenReturn(
                new TopicRoutingService.TopicRoutingResult(
                        routedContext, RouteDecision.CREATE_NEW, BigDecimal.ONE,
                        null, topicContext, false));
        ChatResponse firstChunk = response("北京到");
        ChatResponse secondChunk = response("上海有票");
        when(test.model().stream(any(), any(), any(), eq(false))).thenReturn(Flux.just(
                firstChunk, secondChunk));

        // 元数据先于增量输出，完成事件携带拼接后的完整回答。
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

        // 成功请求应分别记录首事件、首个文本增量和整轮完成耗时。
        org.assertj.core.api.Assertions.assertThat(test.meterRegistry()
                .get("agent.chat.time.to.first.event").timer().count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(test.meterRegistry()
                .get("agent.chat.time.to.first.token").timer().count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(test.meterRegistry()
                .get("agent.chat.requests")
                .tags("outcome", "SUCCESS", "reused", "false")
                .counter().count()).isEqualTo(1);
    }

    /**
     * 验证相同幂等请求已经完成时重放回答且不重复调用路由和模型。
     */
    @Test
    void completedTurnIsReusedWithoutModelCall() {
        TestContext test = context();
        ChatCommand command = command();

        // 幂等入口返回既有轮次，状态查询提供原主题和助手正文。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, false));
        when(test.memory().getTurnState(command.userId(), "turn-1")).thenReturn(
                new ConversationMemoryService.TurnState(
                        TurnStatus.COMPLETED, "topic-1", "已完成的回答"));

        // 重放包含 META、完整 DELTA 和 DONE，且不会再次触发路由或外部模型。
        StepVerifier.create(test.service().stream(command))
                .expectNextCount(2)
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.type()).isEqualTo(EventType.DONE);
                    org.assertj.core.api.Assertions.assertThat(event.reused()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(event.content()).isEqualTo("已完成的回答");
                })
                .verifyComplete();
        verify(test.topicRouting(), never()).route(any(), any());
        verify(test.model(), never()).stream(any(), any(), any(), any(Boolean.class));
    }

    /**
     * 验证模型长期不返回数据时会终止事件流并取消仍在运行的轮次。
     */
    @Test
    void responseTimeoutCancelsRunningTurn() {
        TestContext test = context(Duration.ofMillis(30));
        ChatCommand command = command();
        AgentRequestContext routedContext = new AgentRequestContext(
                command.requestId(), command.userId(), command.username(),
                command.conversationId(), "turn-1", "topic-1");
        TopicContextService.TopicContext topicContext = new TopicContextService.TopicContext(
                command.conversationId(), "topic-1", null, null, null, List.of(), 0);

        // 构造已开始但模型永不产生首包的轮次，覆盖线上连接长期挂起的场景。
        when(test.memory().startTurn(any())).thenReturn(new ConversationMemoryService.StartedTurn(
                command.conversationId(), "turn-1", "message-1", 1L, true));
        when(test.topicRouting().route(any(), eq("message-1"))).thenReturn(
                new TopicRoutingService.TopicRoutingResult(
                        routedContext, RouteDecision.CREATE_NEW, BigDecimal.ONE,
                        null, topicContext, false));
        when(test.model().stream(any(), any(), any(), eq(false))).thenReturn(Flux.never());

        // 超时应返回稳定、安全的业务异常，同时取消数据库中的运行中轮次。
        StepVerifier.create(test.service().stream(command))
                .expectNextMatches(event -> event.type() == EventType.META)
                .expectErrorSatisfies(error -> {
                    org.assertj.core.api.Assertions.assertThat(error)
                            .isInstanceOf(AgentChatException.class)
                            .hasMessage("智能体响应时间过长，本次生成已停止，请稍后重试");
                    org.assertj.core.api.Assertions.assertThat(((AgentChatException) error).failureCategory())
                            .isEqualTo("CHAT_TIMEOUT");
                })
                .verify(Duration.ofSeconds(1));
        verify(test.memory()).cancelTurn(command.userId(), "turn-1");
    }

    /**
     * 创建不包含真实模型和工具连接的编排测试上下文。
     *
     * @return 编排服务及其外部依赖替身
     */
    private TestContext context() {
        return context(Duration.ofSeconds(60));
    }

    /**
     * 使用指定对话超时创建不包含真实模型和工具连接的编排测试上下文。
     *
     * @param responseTimeout 测试使用的整轮响应超时
     * @return 编排服务及其外部依赖替身
     */
    @SuppressWarnings("unchecked")
    private TestContext context(Duration responseTimeout) {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        TopicRoutingService topicRouting = mock(TopicRoutingService.class);
        RoutedChatModelService model = mock(RoutedChatModelService.class);
        PurchaseActionService purchaseActionService = mock(PurchaseActionService.class);
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.empty());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        // 使用固定时钟保证系统提示中的当前日期不会造成测试波动。
        AgentChatService service = new AgentChatService(
                memory,
                topicRouting,
                model,
                purchaseActionService,
                new McpToolContextFactory(),
                providers,
                Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC),
                new AgentChatProperties(responseTimeout),
                new AgentChatMetrics(meterRegistry));
        return new TestContext(service, memory, topicRouting, model, meterRegistry);
    }

    /**
     * 创建稳定的对话测试命令。
     *
     * @return 测试对话命令
     */
    private ChatCommand command() {
        return new ChatCommand(
                "request-1", "request-1", "user-1", "tester",
                "conversation-1", "查询北京到上海的票");
    }

    /**
     * 创建包含指定文本增量的 Spring AI 响应。
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
     * 单个编排测试需要的服务和依赖集合。
     *
     * @param service 待测编排服务
     * @param memory 会话记忆服务替身
     * @param topicRouting 主题路由服务替身
     * @param model 回答模型服务替身
     * @param meterRegistry 测试专用指标注册表
     */
    private record TestContext(
            AgentChatService service,
            ConversationMemoryService memory,
            TopicRoutingService topicRouting,
            RoutedChatModelService model,
            SimpleMeterRegistry meterRegistry) {
    }
}
