package org.opengoofy.index12306.ai.agentservice.chat;

/**
 * 对话入口使用的请求、响应和流式事件模型集合。
 */
public final class AgentChatModels {

    /**
     * 工具类不允许实例化。
     */
    private AgentChatModels() {
    }

    /**
     * 创建会话请求。
     *
     * @param title 可选会话标题
     */
    public record CreateConversationRequest(String title) {
    }

    /**
     * 创建会话响应。
     *
     * @param conversationId 会话标识
     */
    public record CreateConversationResponse(String conversationId) {
    }

    /**
     * 用户对话请求。
     *
     * @param conversationId 会话标识
     * @param message 当前用户问题
     */
    public record ChatRequest(String conversationId, String message) {
    }

    /**
     * 编排层使用的完整对话命令。
     *
     * @param requestId 请求标识
     * @param idempotencyKey 幂等键
     * @param userId 用户标识
     * @param username 用户名
     * @param conversationId 会话标识
     * @param message 当前用户问题
     */
    public record ChatCommand(
            String requestId,
            String idempotencyKey,
            String userId,
            String username,
            String conversationId,
            String message) {
    }

    /**
     * 非流式完整回答。
     *
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param topicId 主题标识
     * @param content 最终助手回答
     * @param reused 是否复用幂等请求的既有回答
     */
    public record ChatResult(
            String requestId,
            String conversationId,
            String turnId,
            String topicId,
            String content,
            boolean reused) {
    }

    /**
     * 流式事件类型。
     */
    public enum EventType {
        META,
        DELTA,
        DONE,
        ERROR
    }

    /**
     * SSE 对话事件。
     *
     * @param type 事件类型
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param topicId 主题标识
     * @param delta 当前增量文本
     * @param content 最终完整文本
     * @param reused 是否复用既有回答
     * @param failureCategory 稳定失败分类
     * @param message 安全的用户提示
     */
    public record ChatEvent(
            EventType type,
            String requestId,
            String conversationId,
            String turnId,
            String topicId,
            String delta,
            String content,
            boolean reused,
            String failureCategory,
            String message) {

        /**
         * 创建开始输出前的元数据事件。
         *
         * @param context 已确定主题的请求上下文
         * @param reused 是否复用既有回答
         * @return 元数据事件
         */
        public static ChatEvent meta(
                org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext context,
                boolean reused) {
            return new ChatEvent(
                    EventType.META, context.requestId(), context.conversationId(), context.turnId(),
                    context.topicId(), null, null, reused, null, null);
        }

        /**
         * 创建单个回答增量事件。
         *
         * @param context 已确定主题的请求上下文
         * @param delta 增量正文
         * @return 增量事件
         */
        public static ChatEvent delta(
                org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext context,
                String delta) {
            return new ChatEvent(
                    EventType.DELTA, context.requestId(), context.conversationId(), context.turnId(),
                    context.topicId(), delta, null, false, null, null);
        }

        /**
         * 创建回答完成事件。
         *
         * @param context 已确定主题的请求上下文
         * @param content 完整回答
         * @param reused 是否复用既有回答
         * @return 完成事件
         */
        public static ChatEvent done(
                org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext context,
                String content,
                boolean reused) {
            return new ChatEvent(
                    EventType.DONE, context.requestId(), context.conversationId(), context.turnId(),
                    context.topicId(), null, content, reused, null, null);
        }

        /**
         * 创建不暴露内部异常正文的失败事件。
         *
         * @param command 原始对话命令
         * @param category 稳定失败分类
         * @param safeMessage 安全的用户提示
         * @return 失败事件
         */
        public static ChatEvent error(ChatCommand command, String category, String safeMessage) {
            return new ChatEvent(
                    EventType.ERROR, command.requestId(), command.conversationId(), null, null,
                    null, null, false, category, safeMessage);
        }
    }

    /**
     * 统一错误响应。
     *
     * @param requestId 请求标识
     * @param failureCategory 稳定失败分类
     * @param message 安全的用户提示
     */
    public record ErrorResponse(String requestId, String failureCategory, String message) {
    }
}
