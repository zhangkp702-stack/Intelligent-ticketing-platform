package org.opengoofy.index12306.ai.agentservice.model.observability;

/**
 * 模型尝试与业务请求之间的显式关联信息，不包含提示词和响应正文。
 *
 * @param requestId 请求或异步任务标识
 * @param conversationId 会话标识
 * @param topicId 主题标识
 * @param turnId 轮次标识
 */
public record ModelAttemptContext(
        String requestId,
        String conversationId,
        String topicId,
        String turnId) {

    private static final ModelAttemptContext EMPTY = new ModelAttemptContext(null, null, null, null);

    /**
     * 返回不关联业务请求的空上下文，供健康检查和既有调用兼容使用。
     *
     * @return 共享的空模型尝试上下文
     */
    public static ModelAttemptContext empty() {
        return EMPTY;
    }
}
