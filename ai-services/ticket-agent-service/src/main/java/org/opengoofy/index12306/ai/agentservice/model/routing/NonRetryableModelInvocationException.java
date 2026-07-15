package org.opengoofy.index12306.ai.agentservice.model.routing;

/**
 * 表示由业务输入或调用方逻辑引起、不得通过切换模型掩盖的异常。
 */
public class NonRetryableModelInvocationException extends RuntimeException {

    /**
     * 使用业务错误说明创建不可降级异常。
     *
     * @param message 业务错误说明
     */
    public NonRetryableModelInvocationException(String message) {
        super(message);
    }

    /**
     * 使用业务错误说明和原始异常创建不可降级异常。
     *
     * @param message 业务错误说明
     * @param cause 原始异常
     */
    public NonRetryableModelInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
