package org.opengoofy.index12306.ai.agentservice.chat.exception;


import org.springframework.http.HttpStatus;

/**
 * 携带稳定分类和安全提示的对话边界异常。
 */
public class AgentChatException extends RuntimeException {

    private final HttpStatus status;
    private final String failureCategory;

    /**
     * 创建不会暴露下游异常正文的对话异常。
     *
     * @param status HTTP 状态
     * @param failureCategory 稳定失败分类
     * @param safeMessage 可返回给用户的提示
     */
    public AgentChatException(HttpStatus status, String failureCategory, String safeMessage) {
        super(safeMessage);
        this.status = status;
        this.failureCategory = failureCategory;
    }

    /**
     * 返回建议的 HTTP 状态。
     *
     * @return HTTP 状态
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * 返回稳定失败分类。
     *
     * @return 失败分类
     */
    public String failureCategory() {
        return failureCategory;
    }
}
