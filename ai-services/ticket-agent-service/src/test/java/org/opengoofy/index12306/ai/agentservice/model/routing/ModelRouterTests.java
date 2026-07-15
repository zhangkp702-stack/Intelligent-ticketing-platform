package org.opengoofy.index12306.ai.agentservice.model.routing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.model.client.ModelClientRegistry;
import org.opengoofy.index12306.ai.agentservice.model.client.RoutedModelClient;
import org.opengoofy.index12306.ai.agentservice.model.config.AgentModelProperties;
import org.opengoofy.index12306.ai.agentservice.model.config.ModelCapability;
import org.opengoofy.index12306.ai.agentservice.model.config.ModelRole;
import org.opengoofy.index12306.ai.agentservice.model.observability.ModelAttemptOutcome;
import org.opengoofy.index12306.ai.agentservice.model.observability.ModelAttemptRecorder;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多模型角色路由和流式降级行为测试。
 */
class ModelRouterTests {

    /**
     * 验证主模型发生网络故障后切换到次模型，并保留完整尝试审计。
     */
    @Test
    void synchronousCallFallsBackToSecondaryModel() {
        TestContext context = context();

        // 主模型模拟网络异常，次模型返回结果，验证候选顺序和最终元数据。
        ModelCallResult<String> result = context.router().execute(
                ModelRole.ANSWER_TOOL,
                Set.of(),
                client -> {
                    if (client.candidateId().equals("primary")) {
                        throw new ResourceAccessException("network");
                    }
                    return "secondary-result";
                });

        assertThat(result.value()).isEqualTo("secondary-result");
        assertThat(result.candidateId()).isEqualTo("secondary");
        assertThat(result.fallbackIndex()).isEqualTo(1);
        assertThat(context.recorder().recent())
                .extracting(event -> event.outcome())
                .containsExactly(ModelAttemptOutcome.SUCCESS, ModelAttemptOutcome.FAILURE);
    }

    /**
     * 验证业务异常立即终止，不会调用降级链中的后续模型。
     */
    @Test
    void businessFailureDoesNotFallback() {
        TestContext context = context();
        AtomicInteger secondaryCalls = new AtomicInteger();

        // 主模型调用阶段抛出不可降级业务异常，次模型计数必须保持为零。
        assertThatThrownBy(() -> context.router().execute(
                ModelRole.ANSWER_TOOL,
                Set.of(),
                client -> {
                    if (client.candidateId().equals("primary")) {
                        throw new NonRetryableModelInvocationException("invalid business input");
                    }
                    secondaryCalls.incrementAndGet();
                    return "unexpected";
                }))
                .isInstanceOf(ModelRoutingException.class)
                .satisfies(exception -> assertThat(((ModelRoutingException) exception).failureCategory())
                        .isEqualTo(ModelFailureCategory.BUSINESS));
        assertThat(secondaryCalls).hasValue(0);
    }

    /**
     * 验证流式请求在主模型输出首包之前失败时可以安全切换次模型。
     */
    @Test
    void streamFallsBackBeforeFirstChunk() {
        TestContext context = context();

        // 主模型在输出任何数据前失败，次模型应成为用户看到的唯一响应来源。
        Flux<String> response = context.router().stream(
                ModelRole.ANSWER_TOOL,
                Set.of(ModelCapability.STREAMING),
                client -> client.candidateId().equals("primary")
                        ? Flux.error(new ResourceAccessException("network"))
                        : Flux.just("secondary-chunk"));

        StepVerifier.create(response)
                .expectNext("secondary-chunk")
                .verifyComplete();
        assertThat(context.recorder().recent())
                .extracting(event -> event.outcome())
                .containsExactly(ModelAttemptOutcome.SUCCESS, ModelAttemptOutcome.FAILURE);
    }

    /**
     * 验证流式请求已经输出首包后发生错误时不会拼接其他模型的回答。
     */
    @Test
    void streamDoesNotFallbackAfterFirstChunk() {
        TestContext context = context();
        AtomicInteger secondaryCalls = new AtomicInteger();

        // 主模型先输出一个数据块再失败，路由器只能向用户返回终止错误。
        Flux<String> response = context.router().stream(
                ModelRole.ANSWER_TOOL,
                Set.of(ModelCapability.STREAMING),
                client -> {
                    if (client.candidateId().equals("primary")) {
                        return Flux.concat(
                                Flux.just("primary-chunk"),
                                Flux.error(new ResourceAccessException("network")));
                    }
                    secondaryCalls.incrementAndGet();
                    return Flux.just("unexpected-secondary-chunk");
                });

        StepVerifier.create(response)
                .expectNext("primary-chunk")
                .expectError(ModelRoutingException.class)
                .verify();
        assertThat(secondaryCalls).hasValue(0);
        assertThat(context.recorder().recent().get(0).firstChunkEmitted()).isTrue();
    }

    /**
     * 创建包含两个不同平台候选模型的路由测试上下文。
     *
     * @return 可独立执行测试的路由器和审计记录器
     */
    private TestContext context() {
        AgentModelProperties properties = properties();
        RoutedModelClient primary = client(
                "primary", "bailian", "answer-primary");
        RoutedModelClient secondary = client(
                "secondary", "siliconflow", "answer-secondary");
        ModelClientRegistry registry = mock(ModelClientRegistry.class);
        when(registry.find("primary")).thenReturn(Optional.of(primary));
        when(registry.find("secondary")).thenReturn(Optional.of(secondary));

        // 使用真实熔断器、并发隔离器和指标记录器，只替换外部模型网络客户端。
        ModelAttemptRecorder recorder = new ModelAttemptRecorder(new SimpleMeterRegistry(), properties);
        ModelRouter router = new ModelRouter(
                properties,
                registry,
                new ModelHealthTracker(properties),
                new ProviderConcurrencyLimiter(properties),
                new ModelFailureClassifier(),
                recorder);
        return new TestContext(router, recorder);
    }

    /**
     * 创建路由测试使用的双平台配置。
     *
     * @return 双候选回答链配置
     */
    private AgentModelProperties properties() {
        AgentModelProperties.Provider bailian = provider("https://dashscope.aliyuncs.com/compatible-mode");
        AgentModelProperties.Provider siliconflow = provider("https://api.siliconflow.cn");
        Set<ModelCapability> capabilities = Set.of(ModelCapability.CHAT, ModelCapability.STREAMING);
        AgentModelProperties.Candidate primary = new AgentModelProperties.Candidate(
                true, "bailian", "answer-primary", capabilities, 0.2, 1024, Map.of());
        AgentModelProperties.Candidate secondary = new AgentModelProperties.Candidate(
                true, "siliconflow", "answer-secondary", capabilities, 0.2, 1024, Map.of());

        // 仅 ANSWER_TOOL 角色参与单元测试，其他角色不经过配置校验器。
        return new AgentModelProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofMillis(10),
                2,
                Duration.ofSeconds(10),
                20,
                Map.of("bailian", bailian, "siliconflow", siliconflow),
                Map.of("primary", primary, "secondary", secondary),
                Map.of(ModelRole.ANSWER_TOOL, List.of("primary", "secondary")));
    }

    /**
     * 创建带虚拟密钥的测试平台配置，测试不会真正发起网络请求。
     *
     * @param baseUrl 平台根地址
     * @return 平台配置
     */
    private AgentModelProperties.Provider provider(String baseUrl) {
        // 并发上限设为一，用于同时验证失败后许可能够及时释放给降级请求。
        return new AgentModelProperties.Provider(
                true,
                URI.create(baseUrl),
                "test-key",
                "/v1/chat/completions",
                Duration.ofMillis(100),
                Duration.ofSeconds(2),
                1);
    }

    /**
     * 创建只承载路由元数据的模型客户端，外部调用行为由测试 lambda 提供。
     *
     * @param candidateId 候选项标识
     * @param providerId 平台标识
     * @param modelId 模型标识
     * @return 测试模型客户端
     */
    private RoutedModelClient client(String candidateId, String providerId, String modelId) {
        // ChatModel 不会在通用路由测试中直接调用，只需提供类型完整的占位实例。
        return new RoutedModelClient(
                candidateId,
                providerId,
                modelId,
                Set.of(ModelCapability.CHAT, ModelCapability.STREAMING),
                mock(ChatModel.class));
    }

    /**
     * 单个测试所需的模型路由器与审计记录器组合。
     *
     * @param router 待测路由器
     * @param recorder 内存审计记录器
     */
    private record TestContext(ModelRouter router, ModelAttemptRecorder recorder) {
    }
}
