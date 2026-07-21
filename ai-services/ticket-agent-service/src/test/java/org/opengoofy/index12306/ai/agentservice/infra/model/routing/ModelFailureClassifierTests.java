package org.opengoofy.index12306.ai.agentservice.infra.model.routing;

import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelFailureCategory;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.exception.NonRetryableModelInvocationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型异常分类规则测试。
 */
class ModelFailureClassifierTests {

    private final ModelFailureClassifier classifier = new ModelFailureClassifier();

    /**
     * 验证网络、限流和鉴权错误会转换为可用于降级和熔断的稳定分类。
     */
    @Test
    void transientProviderFailuresAreClassified() {
        // 分别模拟本地网络错误和平台 HTTP 错误，验证路由决策所需分类。
        assertThat(classifier.classify(new ResourceAccessException("network")))
                .isEqualTo(ModelFailureCategory.NETWORK);
        assertThat(classifier.classify(httpError(HttpStatus.TOO_MANY_REQUESTS, "rate limit")))
                .isEqualTo(ModelFailureCategory.RATE_LIMIT);
        assertThat(classifier.classify(httpError(HttpStatus.UNAUTHORIZED, "invalid key")))
                .isEqualTo(ModelFailureCategory.AUTHENTICATION);
    }

    /**
     * 验证上下文超限能够与普通无效参数区分，允许上层选择不同恢复策略。
     */
    @Test
    void contextLengthErrorIsRecognized() {
        // 模拟 OpenAI 兼容接口返回的上下文窗口错误正文。
        HttpClientErrorException exception = httpError(
                HttpStatus.BAD_REQUEST, "maximum context length exceeded");

        assertThat(classifier.classify(exception)).isEqualTo(ModelFailureCategory.CONTEXT_LENGTH);
    }

    /**
     * 验证显式业务异常不会被误判为平台故障并触发模型切换。
     */
    @Test
    void businessFailureIsNotFallbackEligible() {
        // 业务输入错误必须原样终止，不能通过其他模型掩盖。
        ModelFailureCategory category = classifier.classify(
                new NonRetryableModelInvocationException("business"));

        assertThat(category).isEqualTo(ModelFailureCategory.BUSINESS);
        assertThat(category.fallbackAllowed()).isFalse();
    }

    /**
     * 创建带指定状态和响应正文的 HTTP 客户端异常。
     *
     * @param status HTTP 状态
     * @param body 平台错误正文
     * @return 可供分类器处理的异常
     */
    private HttpClientErrorException httpError(HttpStatus status, String body) {
        // 使用真实 Spring HTTP 异常，覆盖生产代码读取状态和正文的路径。
        return HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
