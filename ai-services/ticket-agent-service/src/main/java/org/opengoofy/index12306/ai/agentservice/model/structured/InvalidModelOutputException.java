package org.opengoofy.index12306.ai.agentservice.model.structured;

/**
 * 模型返回内容无法满足约定结构时抛出的可降级异常。
 */
public class InvalidModelOutputException extends RuntimeException {

    /**
     * 使用安全错误说明创建异常，不携带模型返回正文。
     *
     * @param message 不包含模型正文的错误说明
     */
    public InvalidModelOutputException(String message) {
        super(message);
    }

    /**
     * 使用安全错误说明和解析原因创建异常，不携带模型返回正文。
     *
     * @param message 不包含模型正文的错误说明
     * @param cause 底层解析异常
     */
    public InvalidModelOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
