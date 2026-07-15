package org.opengoofy.index12306.ai.agentservice.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.memory.config.AgentMemoryProperties;
import org.opengoofy.index12306.ai.agentservice.memory.domain.RouteDecision;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TopicEntity;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ContextRouteLogRepository;
import org.opengoofy.index12306.ai.agentservice.model.routing.ModelCallResult;
import org.opengoofy.index12306.ai.agentservice.model.structured.StructuredModelInvoker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证主题路由的首主题确定性创建和低置信度兜底规则。
 */
class TopicRoutingServiceTests {

    private final TopicContextService topicContextService = mock(TopicContextService.class);
    private final ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
    private final ContextRouteLogRepository routeLogRepository = mock(ContextRouteLogRepository.class);
    private final StructuredModelInvoker structuredModelInvoker = mock(StructuredModelInvoker.class);
    private final AgentMemoryProperties properties = new AgentMemoryProperties(
            6, 0.55, 6, 16, 12_000, 12, 3,
            Duration.ofSeconds(30), Duration.ofMinutes(2),
            new AgentMemoryProperties.SummaryExecutor(2, 4, 200, Duration.ofSeconds(20)));
    private final TopicRoutingService service = new TopicRoutingService(
            properties,
            topicContextService,
            conversationMemoryService,
            routeLogRepository,
            structuredModelInvoker,
            new ObjectMapper().findAndRegisterModules());

    /**
     * 验证会话没有候选主题时直接创建主题，不调用主题路由模型。
     */
    @Test
    void createsFirstTopicWithoutCallingModel() {
        AgentRequestContext context = context("request-first");
        TopicContextService.TopicRouteInput input = new TopicContextService.TopicRouteInput(
                context.conversationId(), null, "message-1", "查询明天北京到上海的票", List.of(), List.of());
        TopicEntity topic = TopicEntity.create(
                context.conversationId(), "topic-key", "查询明天北京到上海的票", Instant.now());
        TopicContextService.TopicContext loadedContext = new TopicContextService.TopicContext(
                context.conversationId(), topic.getId(), null, null, null, List.of(), 0);
        when(routeLogRepository.findByRequestId(context.requestId())).thenReturn(Optional.empty());
        when(topicContextService.buildRouteInput(
                context.userId(), context.conversationId(), "message-1")).thenReturn(input);
        when(conversationMemoryService.createTopic(
                any(), any(), any(), any())).thenReturn(topic);
        when(topicContextService.loadTopicContext(
                context.userId(), context.requestId(), context.conversationId(), topic.getId()))
                .thenReturn(loadedContext);

        // 首主题采用确定性路径，避免一次没有选择价值的模型调用。
        TopicRoutingService.TopicRoutingResult result = service.route(context, "message-1");

        assertThat(result.decision()).isEqualTo(RouteDecision.CREATE_NEW);
        assertThat(result.requestContext().topicId()).isEqualTo(topic.getId());
        verify(structuredModelInvoker, never()).call(any(), any(), any(), any(), any());
        verify(conversationMemoryService).assignTurnToTopic(
                context.userId(), context.turnId(), topic.getId());
    }

    /**
     * 验证模型置信度低于阈值时沿用仍在候选集合中的活动主题。
     */
    @Test
    void fallsBackToActiveTopicWhenConfidenceIsLow() {
        AgentRequestContext context = context("request-low-confidence");
        String activeTopicId = "topic-active";
        TopicContextService.TopicSummaryCard card = new TopicContextService.TopicSummaryCard(
                activeTopicId, "北京到上海", "余票查询", "{}", 1, Instant.now());
        TopicContextService.TopicRouteInput input = new TopicContextService.TopicRouteInput(
                context.conversationId(), activeTopicId, "message-2", "二等座呢", List.of(), List.of(card));
        TopicContextService.TopicContext loadedContext = new TopicContextService.TopicContext(
                context.conversationId(), activeTopicId, "{}", null, null, List.of(), 0);
        TopicRoutingService.TopicRouteModelOutput output = new TopicRoutingService.TopicRouteModelOutput(
                "CREATE_NEW", null, "新主题", 0.30, "不确定");
        when(routeLogRepository.findByRequestId(context.requestId())).thenReturn(Optional.empty());
        when(topicContextService.buildRouteInput(
                context.userId(), context.conversationId(), "message-2")).thenReturn(input);
        when(structuredModelInvoker.call(any(), any(), any(), any(), any()))
                .thenReturn(new ModelCallResult<>(
                        output, "route-primary", "siliconflow", "Qwen/Qwen3.5-9B",
                        0, Duration.ofMillis(20), "model-call-route"));
        when(topicContextService.loadTopicContext(
                context.userId(), context.requestId(), context.conversationId(), activeTopicId))
                .thenReturn(loadedContext);

        // 低置信度选择由后端规则改写，不允许模型直接指定 FALLBACK_ACTIVE。
        TopicRoutingService.TopicRoutingResult result = service.route(context, "message-2");

        assertThat(result.decision()).isEqualTo(RouteDecision.FALLBACK_ACTIVE);
        assertThat(result.requestContext().topicId()).isEqualTo(activeTopicId);
        assertThat(result.modelCallId()).isEqualTo("model-call-route");
        verify(conversationMemoryService, never()).createTopic(any(), any(), any(), any());
    }

    /**
     * 创建测试请求显式上下文。
     *
     * @param requestId 请求标识
     * @return 完整请求上下文
     */
    private AgentRequestContext context(String requestId) {
        // 单元测试通过显式字段传递身份，确保主题服务不依赖线程上下文。
        return new AgentRequestContext(
                requestId, "user-1", "tester", "conversation-1", "turn-1", null);
    }
}
