package org.opengoofy.index12306.ai.agentservice.infra.model.routing;

import org.opengoofy.index12306.ai.agentservice.infra.chat.ModelClientRegistry;
import org.opengoofy.index12306.ai.agentservice.infra.chat.RoutedModelClient;
import org.opengoofy.index12306.ai.agentservice.infra.config.AgentModelProperties;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelCapability;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelFailureCategory;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelRole;
import org.opengoofy.index12306.ai.agentservice.infra.model.observability.ModelAttemptContext;
import org.opengoofy.index12306.ai.agentservice.infra.model.observability.ModelAttemptEvent;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelAttemptOutcome;
import org.opengoofy.index12306.ai.agentservice.infra.model.observability.ModelAttemptRecorder;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.exception.ModelRoutingException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按角色、能力、健康状态和配置优先级选择模型，并执行同步或首包前流式降级。
 */
@Service
public class ModelRouter {

    private final AgentModelProperties properties;
    private final ModelClientRegistry clientRegistry;
    private final ModelHealthTracker healthTracker;
    private final ProviderConcurrencyLimiter concurrencyLimiter;
    private final ModelFailureClassifier failureClassifier;
    private final ModelAttemptRecorder attemptRecorder;

    /**
     * 创建模型角色路由器。
     *
     * @param properties 模型路由配置
     * @param clientRegistry 已配置客户端注册表
     * @param healthTracker 模型熔断状态跟踪器
     * @param concurrencyLimiter 平台并发隔离器
     * @param failureClassifier 模型异常分类器
     * @param attemptRecorder 模型尝试审计记录器
     */
    public ModelRouter(
            AgentModelProperties properties,
            ModelClientRegistry clientRegistry,
            ModelHealthTracker healthTracker,
            ProviderConcurrencyLimiter concurrencyLimiter,
            ModelFailureClassifier failureClassifier,
            ModelAttemptRecorder attemptRecorder) {
        this.properties = properties;
        this.clientRegistry = clientRegistry;
        this.healthTracker = healthTracker;
        this.concurrencyLimiter = concurrencyLimiter;
        this.failureClassifier = failureClassifier;
        this.attemptRecorder = attemptRecorder;
    }

    /**
     * 按角色降级链执行同步模型调用，成功后返回最终模型选择信息。
     *
     * @param role 模型角色
     * @param additionalCapabilities 本次调用额外要求的能力
     * @param invocation 实际模型调用逻辑
     * @param <T> 调用结果类型
     * @return 模型结果和最终选择元数据
     */
    public <T> ModelCallResult<T> execute(
            ModelRole role,
            Set<ModelCapability> additionalCapabilities,
            ModelInvocation<T> invocation) {
        // 兼容不需要业务关联信息的既有调用，由显式上下文重载统一执行路由。
        return execute(role, additionalCapabilities, ModelAttemptContext.empty(), invocation);
    }

    /**
     * 按角色降级链执行同步模型调用，并将每次尝试关联到显式业务上下文。
     *
     * @param role 模型角色
     * @param additionalCapabilities 本次调用额外要求的能力
     * @param attemptContext 不含正文的模型审计关联信息
     * @param invocation 实际模型调用及结果转换逻辑
     * @param <T> 调用结果类型
     * @return 模型结果、最终选择元数据和成功审计标识
     */
    public <T> ModelCallResult<T> execute(
            ModelRole role,
            Set<ModelCapability> additionalCapabilities,
            ModelAttemptContext attemptContext,
            ModelInvocation<T> invocation) {
        long routingStarted = System.nanoTime();
        long deadline = routingStarted + properties.totalTimeout().toNanos();
        Set<ModelCapability> requiredCapabilities = requiredCapabilities(role, additionalCapabilities);
        List<String> attemptedCandidates = new ArrayList<>();
        Throwable lastFailure = null;
        ModelFailureCategory lastCategory = ModelFailureCategory.MODEL_UNAVAILABLE;
        List<String> route = properties.routes().get(role);

        // 严格按照角色配置顺序尝试候选模型，跳过未配置、能力不足或已熔断的候选项。
        for (int index = 0; index < route.size(); index++) {
            if (System.nanoTime() >= deadline) {
                lastCategory = ModelFailureCategory.TIMEOUT;
                lastFailure = new TimeoutException("model routing total timeout");
                break;
            }
            String candidateId = route.get(index);
            Optional<RoutedModelClient> clientOptional = eligibleClient(role, candidateId, requiredCapabilities);
            if (clientOptional.isEmpty()) {
                continue;
            }
            RoutedModelClient client = clientOptional.get();
            Optional<ProviderConcurrencyLimiter.Permit> permitOptional = concurrencyLimiter.acquire(client.providerId());
            if (permitOptional.isEmpty()) {
                healthTracker.releaseProbe(role, candidateId);
                recordFailure(role, client, index, 0, false, ModelFailureCategory.PROVIDER_BUSY,
                        attemptContext,
                        new java.util.concurrent.RejectedExecutionException());
                lastCategory = ModelFailureCategory.PROVIDER_BUSY;
                continue;
            }

            attemptedCandidates.add(candidateId);
            long attemptStarted = System.nanoTime();
            try (ProviderConcurrencyLimiter.Permit ignored = permitOptional.get()) {
                // 实际请求只调用一次，HTTP 读取超时负责限制单候选等待时间。
                T value = invocation.invoke(client);
                if (value == null) {
                    throw new EmptyModelResponseException();
                }
                long attemptMillis = elapsedMillis(attemptStarted);
                healthTracker.recordSuccess(role, candidateId);
                String modelCallId = recordSuccess(
                        role, client, index, attemptMillis, false, attemptContext);
                return new ModelCallResult<>(
                        value,
                        candidateId,
                        client.providerId(),
                        client.modelId(),
                        index,
                        Duration.ofNanos(System.nanoTime() - routingStarted),
                        modelCallId);
            } catch (Exception ex) {
                long attemptMillis = elapsedMillis(attemptStarted);
                ModelFailureCategory category = failureClassifier.classify(ex);
                healthTracker.recordFailure(role, candidateId, category);
                recordFailure(role, client, index, attemptMillis, false, category, attemptContext, ex);
                lastFailure = ex;
                lastCategory = category;
                if (!category.fallbackAllowed()) {
                    throw routingFailure(role, attemptedCandidates, category, ex);
                }
            }
        }
        throw routingFailure(role, attemptedCandidates, lastCategory, lastFailure);
    }

    /**
     * 创建按角色降级的模型响应流；只有在尚未输出首包时才允许切换候选模型。
     *
     * @param role 模型角色
     * @param additionalCapabilities 本次调用额外要求的能力
     * @param invocation 实际流式模型调用逻辑
     * @param <T> 响应数据块类型
     * @return 具备首包前降级能力的响应流
     */
    public <T> Flux<T> stream(
            ModelRole role,
            Set<ModelCapability> additionalCapabilities,
            ModelStreamInvocation<T> invocation) {
        // 未提供业务关联信息的旧调用保持原有行为。
        return stream(role, additionalCapabilities, ModelAttemptContext.empty(), invocation);
    }

    /**
     * 使用显式审计上下文创建按角色降级的模型响应流。
     *
     * @param role 模型角色
     * @param additionalCapabilities 本次调用额外要求的能力
     * @param attemptContext 不包含正文的业务审计关联信息
     * @param invocation 实际流式模型调用逻辑
     * @param <T> 响应数据块类型
     * @return 具备首包前降级能力的响应流
     */
    public <T> Flux<T> stream(
            ModelRole role,
            Set<ModelCapability> additionalCapabilities,
            ModelAttemptContext attemptContext,
            ModelStreamInvocation<T> invocation) {
        // 把同一业务上下文传递到整条候选链，确保降级尝试可关联到同一轮对话。
        Set<ModelCapability> required = requiredCapabilities(role, additionalCapabilities);
        List<String> attemptedCandidates = new ArrayList<>();
        long routingStarted = System.nanoTime();
        return streamFrom(
                role, required, attemptContext, invocation, 0, routingStarted, attemptedCandidates);
    }

    /**
     * 从指定降级链位置开始递归创建响应流。
     *
     * @param role 模型角色
     * @param requiredCapabilities 完整能力要求
     * @param attemptContext 不包含正文的业务审计关联信息
     * @param invocation 流式调用逻辑
     * @param startIndex 开始搜索的候选项位置
     * @param routingStarted 路由开始时间
     * @param attemptedCandidates 已尝试候选项
     * @param <T> 响应数据块类型
     * @return 当前候选或后续降级候选的响应流
     */
    private <T> Flux<T> streamFrom(
            ModelRole role,
            Set<ModelCapability> requiredCapabilities,
            ModelAttemptContext attemptContext,
            ModelStreamInvocation<T> invocation,
            int startIndex,
            long routingStarted,
            List<String> attemptedCandidates) {
        return Flux.defer(() -> {
            long elapsedNanos = System.nanoTime() - routingStarted;
            long remainingNanos = properties.totalTimeout().toNanos() - elapsedNanos;
            if (remainingNanos <= 0) {
                TimeoutException timeout = new TimeoutException("model routing total timeout");
                return Flux.error(routingFailure(
                        role, attemptedCandidates, ModelFailureCategory.TIMEOUT, timeout));
            }

            // 查找下一项真正可调用的候选模型，配置缺失和熔断项不会产生外部请求。
            List<String> route = properties.routes().get(role);
            for (int index = startIndex; index < route.size(); index++) {
                String candidateId = route.get(index);
                Optional<RoutedModelClient> clientOptional = eligibleClient(role, candidateId, requiredCapabilities);
                if (clientOptional.isEmpty()) {
                    continue;
                }
                RoutedModelClient client = clientOptional.get();
                Optional<ProviderConcurrencyLimiter.Permit> permitOptional = concurrencyLimiter.acquire(client.providerId());
                if (permitOptional.isEmpty()) {
                    healthTracker.releaseProbe(role, candidateId);
                    recordFailure(role, client, index, 0, false, ModelFailureCategory.PROVIDER_BUSY,
                            attemptContext,
                            new java.util.concurrent.RejectedExecutionException());
                    continue;
                }
                attemptedCandidates.add(candidateId);
                return invokeStreamCandidate(
                        role,
                        requiredCapabilities,
                        attemptContext,
                        invocation,
                        index,
                        routingStarted,
                        attemptedCandidates,
                        client,
                        permitOptional.get(),
                        remainingNanos);
            }
            return Flux.error(routingFailure(
                    role, attemptedCandidates, ModelFailureCategory.MODEL_UNAVAILABLE, null));
        });
    }

    /**
     * 调用单个流式候选，并在首包前发生可降级异常时切换到下一候选项。
     *
     * @param role 模型角色
     * @param requiredCapabilities 完整能力要求
     * @param attemptContext 不包含正文的业务审计关联信息
     * @param invocation 流式调用逻辑
     * @param index 当前候选项位置
     * @param routingStarted 路由开始时间
     * @param attemptedCandidates 已尝试候选项
     * @param client 当前模型客户端
     * @param permit 平台并发许可
     * @param remainingNanos 剩余路由时间预算
     * @param <T> 响应数据块类型
     * @return 当前候选或降级候选的响应流
     */
    private <T> Flux<T> invokeStreamCandidate(
            ModelRole role,
            Set<ModelCapability> requiredCapabilities,
            ModelAttemptContext attemptContext,
            ModelStreamInvocation<T> invocation,
            int index,
            long routingStarted,
            List<String> attemptedCandidates,
            RoutedModelClient client,
            ProviderConcurrencyLimiter.Permit permit,
            long remainingNanos) {
        long attemptStarted = System.nanoTime();
        AtomicBoolean emitted = new AtomicBoolean();
        AtomicBoolean terminalRecorded = new AtomicBoolean();
        Duration firstChunkTimeout = Duration.ofNanos(Math.min(
                properties.attemptTimeout().toNanos(), remainingNanos));

        Flux<T> source;
        try {
            source = invocation.invoke(client);
        } catch (RuntimeException ex) {
            permit.close();
            return handleStreamFailure(
                    role, requiredCapabilities, attemptContext, invocation, index, routingStarted,
                    attemptedCandidates, client, ex, false);
        }

        // 超时仅约束首包；一旦开始向用户输出，就禁止自动换模型以免拼接不同回答。
        return source
                .switchIfEmpty(Flux.error(new EmptyModelResponseException()))
                .timeout(Mono.delay(firstChunkTimeout), ignored -> Mono.never())
                .doOnNext(ignored -> emitted.set(true))
                .doOnComplete(() -> {
                    // 正常结束后立即归还平台许可，再更新成功状态和观测记录。
                    permit.close();
                    terminalRecorded.set(true);
                    healthTracker.recordSuccess(role, client.candidateId());
                    recordSuccess(role, client, index, elapsedMillis(attemptStarted), emitted.get(),
                            attemptContext);
                })
                .doOnError(ex -> {
                    // 在首包前降级订阅下一模型之前释放许可，避免同平台降级链自阻塞。
                    permit.close();
                    terminalRecorded.set(true);
                    ModelFailureCategory category = failureClassifier.classify(ex);
                    healthTracker.recordFailure(role, client.candidateId(), category);
                    recordFailure(
                            role, client, index, elapsedMillis(attemptStarted), emitted.get(), category,
                            attemptContext, ex);
                })
                .doFinally(ignored -> {
                    permit.close();
                    if (!terminalRecorded.get()) {
                        healthTracker.releaseProbe(role, client.candidateId());
                    }
                })
                .onErrorResume(ex -> {
                    ModelFailureCategory category = failureClassifier.classify(ex);
                    if (!emitted.get() && category.fallbackAllowed()) {
                        return streamFrom(
                                role,
                                requiredCapabilities,
                                attemptContext,
                                invocation,
                                index + 1,
                                routingStarted,
                                attemptedCandidates);
                    }
                    return Flux.error(routingFailure(role, attemptedCandidates, category, ex));
                });
    }

    /**
     * 处理响应流创建阶段的同步异常，并在允许时继续下一候选模型。
     *
     * @param role 模型角色
     * @param requiredCapabilities 完整能力要求
     * @param attemptContext 不包含正文的业务审计关联信息
     * @param invocation 流式调用逻辑
     * @param index 当前候选项位置
     * @param routingStarted 路由开始时间
     * @param attemptedCandidates 已尝试候选项
     * @param client 当前模型客户端
     * @param exception 创建响应流时的异常
     * @param firstChunkEmitted 是否已经输出首包
     * @param <T> 响应数据块类型
     * @return 降级响应流或终止异常
     */
    private <T> Flux<T> handleStreamFailure(
            ModelRole role,
            Set<ModelCapability> requiredCapabilities,
            ModelAttemptContext attemptContext,
            ModelStreamInvocation<T> invocation,
            int index,
            long routingStarted,
            List<String> attemptedCandidates,
            RoutedModelClient client,
            RuntimeException exception,
            boolean firstChunkEmitted) {
        ModelFailureCategory category = failureClassifier.classify(exception);
        healthTracker.recordFailure(role, client.candidateId(), category);
        recordFailure(role, client, index, 0, firstChunkEmitted, category,
                attemptContext, exception);
        if (!firstChunkEmitted && category.fallbackAllowed()) {
            return streamFrom(
                    role,
                    requiredCapabilities,
                    attemptContext,
                    invocation,
                    index + 1,
                    routingStarted,
                    attemptedCandidates);
        }
        return Flux.error(routingFailure(role, attemptedCandidates, category, exception));
    }

    /**
     * 合并角色基础能力和本次调用额外能力。
     *
     * @param role 模型角色
     * @param additionalCapabilities 额外能力集合
     * @return 合并后的不可变能力集合
     */
    private Set<ModelCapability> requiredCapabilities(
            ModelRole role,
            Set<ModelCapability> additionalCapabilities) {
        EnumSet<ModelCapability> required = EnumSet.copyOf(role.requiredCapabilities());
        if (additionalCapabilities != null) {
            required.addAll(additionalCapabilities);
        }
        return Set.copyOf(required);
    }

    /**
     * 查找启用、已配置、能力匹配且未被熔断的候选模型。
     *
     * @param role 模型角色
     * @param candidateId 候选项标识
     * @param requiredCapabilities 完整能力要求
     * @return 可调用客户端，否则返回空
     */
    private Optional<RoutedModelClient> eligibleClient(
            ModelRole role,
            String candidateId,
            Set<ModelCapability> requiredCapabilities) {
        AgentModelProperties.Candidate candidate = properties.candidates().get(candidateId);
        if (candidate == null || !candidate.enabled() || !candidate.capabilities().containsAll(requiredCapabilities)) {
            return Optional.empty();
        }
        Optional<RoutedModelClient> client = clientRegistry.find(candidateId);
        if (client.isEmpty() || !healthTracker.tryAcquire(role, candidateId)) {
            return Optional.empty();
        }
        return client;
    }

    /**
     * 记录一次成功模型尝试。
     *
     * @param role 模型角色
     * @param client 模型客户端
     * @param fallbackIndex 候选项位置
     * @param durationMillis 尝试耗时
     * @param firstChunkEmitted 是否输出过流式首包
     * @param attemptContext 不含正文的业务审计关联信息
     * @return 成功尝试的持久化审计标识，未启用或写入失败时为空
     */
    private String recordSuccess(
            ModelRole role,
            RoutedModelClient client,
            int fallbackIndex,
            long durationMillis,
            boolean firstChunkEmitted,
            ModelAttemptContext attemptContext) {
        // 返回成功尝试的持久化标识，供调用方建立可追踪关联。
        return attemptRecorder.record(new ModelAttemptEvent(
                Instant.now(),
                role,
                client.candidateId(),
                client.providerId(),
                client.modelId(),
                ModelAttemptOutcome.SUCCESS,
                null,
                durationMillis,
                fallbackIndex,
                firstChunkEmitted,
                null,
                attemptContext));
    }

    /**
     * 记录一次失败模型尝试，仅保存异常类型而不保存可能包含输入内容的异常正文。
     *
     * @param role 模型角色
     * @param client 模型客户端
     * @param fallbackIndex 候选项位置
     * @param durationMillis 尝试耗时
     * @param firstChunkEmitted 是否输出过流式首包
     * @param category 失败分类
     * @param exception 原始异常
     * @param attemptContext 不含正文的业务审计关联信息
     */
    private void recordFailure(
            ModelRole role,
            RoutedModelClient client,
            int fallbackIndex,
            long durationMillis,
            boolean firstChunkEmitted,
            ModelFailureCategory category,
            ModelAttemptContext attemptContext,
            Throwable exception) {
        // 失败记录仅保存异常类型，不保存可能包含用户输入的异常正文。
        attemptRecorder.record(new ModelAttemptEvent(
                Instant.now(),
                role,
                client.candidateId(),
                client.providerId(),
                client.modelId(),
                ModelAttemptOutcome.FAILURE,
                category,
                durationMillis,
                fallbackIndex,
                firstChunkEmitted,
                exception == null ? null : exception.getClass().getName(),
                attemptContext));
    }

    /**
     * 创建不包含平台错误正文的统一路由异常。
     *
     * @param role 模型角色
     * @param attemptedCandidates 已尝试候选项
     * @param category 最终失败分类
     * @param cause 最后一次异常
     * @return 安全的模型路由异常
     */
    private ModelRoutingException routingFailure(
            ModelRole role,
            List<String> attemptedCandidates,
            ModelFailureCategory category,
            Throwable cause) {
        return new ModelRoutingException(
                "模型服务暂时无法完成角色任务: " + role,
                role,
                attemptedCandidates,
                category,
                cause);
    }

    /**
     * 将纳秒时间差转换为非负毫秒数。
     *
     * @param startedNanos 开始时间
     * @return 已耗费毫秒数
     */
    private long elapsedMillis(long startedNanos) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private static final class EmptyModelResponseException extends RuntimeException {
    }
}
