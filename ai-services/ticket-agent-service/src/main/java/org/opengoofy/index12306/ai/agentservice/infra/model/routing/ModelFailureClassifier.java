package org.opengoofy.index12306.ai.agentservice.infra.model.routing;

import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelFailureCategory;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.exception.NonRetryableModelInvocationException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 将 Spring AI、HTTP 和网络异常转换为稳定的模型故障分类。
 */
@Component
public class ModelFailureClassifier {

    /**
     * 沿异常因果链识别最具体的失败原因，并决定后续是否可以降级。
     *
     * @param throwable 模型调用抛出的异常
     * @return 稳定的模型故障分类
     */
    public ModelFailureCategory classify(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;

        // 逐层检查包装异常，优先识别带有 HTTP 状态码或明确业务语义的原因。
        while (current != null && visited.add(current)) {
            ModelFailureCategory category = classifySingle(current);
            if (category != null) {
                return category;
            }
            current = current.getCause();
        }
        return ModelFailureCategory.UNKNOWN;
    }

    /**
     * 对单层异常进行分类，无法识别时返回空以继续检查其原因。
     *
     * @param throwable 当前层异常
     * @return 已识别分类，无法识别时返回 {@code null}
     */
    private ModelFailureCategory classifySingle(Throwable throwable) {
        if (throwable instanceof NonRetryableModelInvocationException) {
            return ModelFailureCategory.BUSINESS;
        }
        if (throwable instanceof RestClientResponseException responseException) {
            return classifyHttp(responseException);
        }
        if (throwable instanceof TimeoutException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof HttpTimeoutException) {
            return ModelFailureCategory.TIMEOUT;
        }
        if (throwable instanceof RejectedExecutionException) {
            return ModelFailureCategory.PROVIDER_BUSY;
        }
        if (throwable instanceof ResourceAccessException || throwable instanceof IOException) {
            return ModelFailureCategory.NETWORK;
        }
        if (throwable instanceof TransientAiException) {
            return ModelFailureCategory.SERVER_ERROR;
        }
        if (throwable instanceof NonTransientAiException) {
            return ModelFailureCategory.INVALID_REQUEST;
        }
        return null;
    }

    /**
     * 根据 OpenAI 兼容接口的 HTTP 状态和错误语义细分失败原因。
     *
     * @param exception HTTP 响应异常
     * @return 对应失败分类
     */
    private ModelFailureCategory classifyHttp(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 401 || status == 403) {
            return ModelFailureCategory.AUTHENTICATION;
        }
        if (status == 404) {
            return ModelFailureCategory.MODEL_UNAVAILABLE;
        }
        if (status == 408 || status == 504) {
            return ModelFailureCategory.TIMEOUT;
        }
        if (status == 429) {
            return ModelFailureCategory.RATE_LIMIT;
        }
        if (status >= 500) {
            return ModelFailureCategory.SERVER_ERROR;
        }
        if (status == 400 || status == 413 || status == 422) {
            return classifyClientError(exception);
        }
        return ModelFailureCategory.UNKNOWN;
    }

    /**
     * 从客户端错误正文中区分上下文超限、内容安全和普通参数错误。
     *
     * @param exception HTTP 客户端错误
     * @return 细分后的失败分类
     */
    private ModelFailureCategory classifyClientError(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString().toLowerCase(Locale.ROOT);
        if (body.contains("context length")
                || body.contains("maximum context")
                || body.contains("too many tokens")
                || body.contains("token limit")) {
            return ModelFailureCategory.CONTEXT_LENGTH;
        }
        if (body.contains("content policy")
                || body.contains("content_filter")
                || body.contains("moderation")
                || body.contains("safety")) {
            return ModelFailureCategory.CONTENT_POLICY;
        }
        return ModelFailureCategory.INVALID_REQUEST;
    }
}
