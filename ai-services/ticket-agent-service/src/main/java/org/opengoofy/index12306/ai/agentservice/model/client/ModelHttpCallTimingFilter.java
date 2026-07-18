package org.opengoofy.index12306.ai.agentservice.model.client;

import org.opengoofy.index12306.ai.agentservice.model.observability.ModelHttpCallTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.context.ContextView;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在 OpenAI 兼容接口的 HTTP 响应边界记录每一轮模型调用的首包和完整耗时。
 */
final class ModelHttpCallTimingFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelHttpCallTimingFilter.class);
    private static final AtomicLong UNCORRELATED_CALL_SEQUENCE = new AtomicLong();

    private ModelHttpCallTimingFilter() {
    }

    /**
     * 为指定模型候选项创建逐轮 HTTP 调用计时过滤器。
     *
     * @param providerId 模型平台标识
     * @param candidateId 路由候选项标识
     * @param modelId 平台模型标识
     * @return 可注册到模型 WebClient 的过滤器
     */
    static ExchangeFilterFunction create(String providerId, String candidateId, String modelId) {
        // 每次 exchange 都对应一次真实模型 HTTP 往返，内部工具递归会自然产生新的调用记录。
        return (request, next) -> Mono.deferContextual(contextView -> {
            CallIdentity identity = createIdentity(contextView);
            long startedNanos = System.nanoTime();
            AtomicLong firstChunkMillis = new AtomicLong(-1);
            AtomicBoolean terminalRecorded = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<HttpStatusCode> status = new AtomicReference<>();

            LOGGER.info(
                    "Agent模型分轮调用开始，requestId={}, conversationId={}, turnId={}, role={}, provider={}, "
                            + "candidate={}, model={}, round={}",
                    safe(identity.requestId()), safe(identity.conversationId()), safe(identity.turnId()),
                    safe(identity.role()), providerId, candidateId, modelId, identity.round());

            return next.exchange(request)
                    .map(response -> instrumentResponse(
                            response, identity, providerId, candidateId, modelId, startedNanos,
                            firstChunkMillis, terminalRecorded, failure, status))
                    .doOnError(exception -> {
                        // 连接、TLS 或响应头阶段失败时没有可包装的响应体，需要在 exchange 边界直接记录。
                        failure.set(exception);
                        recordTerminal(
                                SignalType.ON_ERROR, identity, providerId, candidateId, modelId,
                                startedNanos, firstChunkMillis, terminalRecorded, failure.get(), status.get());
                    });
        });
    }

    /**
     * 包装模型响应体，在不改变响应内容的前提下记录首个数据块和终止信号。
     *
     * @param response 原始模型 HTTP 响应
     * @param identity 当前分轮调用身份
     * @param providerId 模型平台标识
     * @param candidateId 路由候选项标识
     * @param modelId 平台模型标识
     * @param startedNanos HTTP 调用开始时间
     * @param firstChunkMillis 首包耗时容器
     * @param terminalRecorded 是否已记录终态
     * @param failure 响应体读取异常容器
     * @param status HTTP 状态容器
     * @return 保持原状态、响应头和响应体内容的计时响应
     */
    private static ClientResponse instrumentResponse(
            ClientResponse response,
            CallIdentity identity,
            String providerId,
            String candidateId,
            String modelId,
            long startedNanos,
            AtomicLong firstChunkMillis,
            AtomicBoolean terminalRecorded,
            AtomicReference<Throwable> failure,
            AtomicReference<HttpStatusCode> status) {
        // 直接转换 mutate 已持有的原始响应流，避免 bodyToFlux 与 mutate 分别订阅导致响应体被消费两次。
        status.set(response.statusCode());
        return response.mutate()
                .body(body -> body
                        .doOnNext(ignored -> recordFirstChunk(
                                identity, providerId, candidateId, modelId, startedNanos,
                                firstChunkMillis, response.statusCode()))
                        .doOnError(failure::set)
                        .doFinally(signalType -> recordTerminal(
                                signalType, identity, providerId, candidateId, modelId,
                                startedNanos, firstChunkMillis, terminalRecorded, failure.get(), status.get())))
                .build();
    }

    /**
     * 仅在当前 HTTP 调用收到第一个响应数据块时记录一次首包耗时。
     *
     * @param identity 当前分轮调用身份
     * @param providerId 模型平台标识
     * @param candidateId 路由候选项标识
     * @param modelId 平台模型标识
     * @param startedNanos HTTP 调用开始时间
     * @param firstChunkMillis 首包耗时容器
     * @param status HTTP 状态
     */
    private static void recordFirstChunk(
            CallIdentity identity,
            String providerId,
            String candidateId,
            String modelId,
            long startedNanos,
            AtomicLong firstChunkMillis,
            HttpStatusCode status) {
        long elapsedMillis = elapsedMillis(startedNanos);
        if (!firstChunkMillis.compareAndSet(-1, elapsedMillis)) {
            return;
        }

        // 首包耗时用于区分平台排队或推理等待与后续流式文本生成耗时。
        LOGGER.info(
                "Agent模型分轮首包返回，requestId={}, conversationId={}, turnId={}, role={}, provider={}, "
                        + "candidate={}, model={}, round={}, firstChunkMs={}, httpStatus={}",
                safe(identity.requestId()), safe(identity.conversationId()), safe(identity.turnId()),
                safe(identity.role()), providerId, candidateId, modelId, identity.round(), elapsedMillis,
                status.value());
    }

    /**
     * 根据响应体终止信号记录当前真实模型 HTTP 调用的完整耗时。
     *
     * @param signalType Reactor 响应体终止信号
     * @param identity 当前分轮调用身份
     * @param providerId 模型平台标识
     * @param candidateId 路由候选项标识
     * @param modelId 平台模型标识
     * @param startedNanos HTTP 调用开始时间
     * @param firstChunkMillis 首包耗时容器
     * @param terminalRecorded 是否已记录终态
     * @param failure 调用异常
     * @param status HTTP 状态
     */
    private static void recordTerminal(
            SignalType signalType,
            CallIdentity identity,
            String providerId,
            String candidateId,
            String modelId,
            long startedNanos,
            AtomicLong firstChunkMillis,
            AtomicBoolean terminalRecorded,
            Throwable failure,
            HttpStatusCode status) {
        if (!terminalRecorded.compareAndSet(false, true)) {
            return;
        }

        // Spring AI 收到 SSE 的 [DONE] 后会取消原始响应体，已有首包的取消记为正常流结束。
        String outcome;
        if (failure != null || signalType == SignalType.ON_ERROR) {
            outcome = "ERROR";
        } else if (status != null && status.isError()) {
            outcome = "HTTP_ERROR";
        } else if (signalType == SignalType.CANCEL && firstChunkMillis.get() < 0) {
            outcome = "CANCELLED";
        } else if (signalType == SignalType.CANCEL) {
            outcome = "STREAM_ENDED";
        } else {
            outcome = "SUCCESS";
        }
        LOGGER.info(
                "Agent模型分轮调用完成，requestId={}, conversationId={}, turnId={}, role={}, provider={}, "
                        + "candidate={}, model={}, round={}, outcome={}, firstChunkMs={}, durationMs={}, "
                        + "httpStatus={}, exceptionType={}",
                safe(identity.requestId()), safe(identity.conversationId()), safe(identity.turnId()),
                safe(identity.role()), providerId, candidateId, modelId, identity.round(), outcome,
                firstChunkMillis.get(), elapsedMillis(startedNanos), status == null ? -1 : status.value(),
                failure == null ? "-" : failure.getClass().getName());
    }

    /**
     * 从 Reactor 上下文创建当前 HTTP 调用的业务身份和轮次。
     *
     * @param contextView 当前只读 Reactor 上下文
     * @return 可安全写入日志的调用身份
     */
    private static CallIdentity createIdentity(ContextView contextView) {
        // 在线对话使用请求内递增轮次；无业务上下文的流式调用使用进程级序号避免日志无法区分。
        return ModelHttpCallTraceContext.find(contextView)
                .map(trace -> new CallIdentity(
                        trace.requestId(), trace.conversationId(), trace.turnId(),
                        trace.role() == null ? null : trace.role().name(), trace.nextRound()))
                .orElseGet(() -> new CallIdentity(
                        null, null, null, null, UNCORRELATED_CALL_SEQUENCE.incrementAndGet()));
    }

    /**
     * 将单调时钟时间差转换为非负毫秒数。
     *
     * @param startedNanos 调用开始时间
     * @return 已耗费毫秒数
     */
    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    /**
     * 将空日志字段转换为统一占位符。
     *
     * @param value 原始字段值
     * @return 非空日志字段
     */
    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /**
     * 保存单次真实模型 HTTP 调用的业务关联字段和分轮序号。
     *
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param turnId 对话轮次标识
     * @param role 模型业务角色
     * @param round 当前请求内的模型调用轮次
     */
    private record CallIdentity(
            String requestId,
            String conversationId,
            String turnId,
            String role,
            long round) {
    }
}
