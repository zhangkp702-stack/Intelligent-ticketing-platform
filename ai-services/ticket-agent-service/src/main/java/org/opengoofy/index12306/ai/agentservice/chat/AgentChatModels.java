package org.opengoofy.index12306.ai.agentservice.chat;

import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationStatus;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageType;

import java.time.Instant;
import java.util.List;

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
     * @param conversationId 会话标识
     * @param title 会话标题
     * @param status 会话状态
     * @param lastMessageSequence 最近消息序号
     * @param createdAt 创建时间
     * @param updatedAt 最近更新时间
     */
    public record ConversationView(
            String conversationId,
            String title,
            ConversationStatus status,
            long lastMessageSequence,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * @param current 当前页码
     * @param size 每页数量
     * @param total 会话总数
     * @param records 当前页会话
     */
    public record ConversationPage(
            long current,
            long size,
            long total,
            List<ConversationView> records) {
    }

    /**
     * @param messageId 消息标识
     * @param turnId 所属问答轮次
     * @param sequenceNo 会话内消息序号
     * @param role 消息角色
     * @param messageType 消息类型
     * @param content 消息正文
     * @param createdAt 创建时间
     */
    public record HistoryMessageView(
            String messageId,
            String turnId,
            long sequenceNo,
            MessageRole role,
            MessageType messageType,
            String content,
            Instant createdAt) {
    }

    /**
     * @param messages 按消息序号升序排列的当前批次
     * @param nextBeforeSequence 下一页游标
     * @param hasMore 是否还有更早消息
     */
    public record HistoryMessagePage(
            List<HistoryMessageView> messages,
            Long nextBeforeSequence,
            boolean hasMore) {
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
     * 取消指定会话中正在生成的对话轮次。
     *
     * @param conversationId 会话标识
     * @param requestId 本轮生成请求标识
     */
    public record ChatCancelRequest(String conversationId, String requestId) {
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
     * @param content 最终助手回答
     * @param reused 是否复用幂等请求的既有回答
     * @param action 可选的待确认操作视图
     */
    public record ChatResult(
            String requestId,
            String conversationId,
            String turnId,
            String content,
            boolean reused,
            ActionConfirmationView action) {
    }

    /**
     * 流式事件类型。
     */
    public enum EventType {
        META,
        DELTA,
        ACTION_REQUIRED,
        DONE,
        ERROR
    }

    /**
     * 单次对话流水线的性能快照，仅随当前 SSE 完成事件返回，不写入会话历史。
     *
     * @param totalDurationMs 后端整轮处理耗时
     * @param contextDurationMs 会话上下文加载耗时
     * @param rewriteDurationMs 问题改写阶段耗时
     * @param routingDurationMs 问题分流和工具解析耗时
     * @param modelDurationMs 回答模型完整响应流耗时
     * @param completionDurationMs 业务收口和轮次持久化耗时
     * @param rewriteModelInvoked 是否调用了问题改写模型
     * @param rewritten 当前问题是否实际发生改写
     * @param route 本轮选择的问答路径
     * @param matchedGroups 命中的业务工具组
     * @param toolAvailability 业务工具可用状态
     * @param enabledTools 实际提供给回答模型的工具名称
     * @param missingTools 分流要求但当前未注册的工具名称
     * @param modelCalls 回答模型每轮真实 HTTP 调用耗时
     */
    public record ChatPerformance(
            long totalDurationMs,
            long contextDurationMs,
            long rewriteDurationMs,
            long routingDurationMs,
            long modelDurationMs,
            long completionDurationMs,
            boolean rewriteModelInvoked,
            boolean rewritten,
            String route,
            List<String> matchedGroups,
            String toolAvailability,
            List<String> enabledTools,
            List<String> missingTools,
            List<ModelCallPerformance> modelCalls) {
    }

    /**
     * 回答模型单轮真实 HTTP 调用的前端安全视图。
     *
     * @param round 当前回答请求内的模型调用轮次
     * @param providerId 模型平台标识
     * @param candidateId 路由候选项标识
     * @param modelId 平台模型标识
     * @param outcome 调用结果
     * @param firstChunkMillis 首包耗时，未收到首包时为 -1
     * @param durationMillis 完整响应流耗时
     * @param httpStatus HTTP 状态码，未收到响应头时为 -1
     */
    public record ModelCallPerformance(
            long round,
            String providerId,
            String candidateId,
            String modelId,
            String outcome,
            long firstChunkMillis,
            long durationMillis,
            int httpStatus) {
    }

    /**
     * SSE 对话事件。
     *
     * @param type 事件类型
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param delta 当前增量文本
     * @param content 最终完整文本
     * @param reused 是否复用既有回答
     * @param failureCategory 稳定失败分类
     * @param message 安全的用户提示
     * @param action 仅在 ACTION_REQUIRED 事件中返回的确认视图
     * @param performance 仅在新生成回答的 DONE 事件中返回的本轮性能快照
     */
    public record ChatEvent(
            EventType type,
            String requestId,
            String conversationId,
            String turnId,
            String delta,
            String content,
            boolean reused,
            String failureCategory,
            String message,
            ActionConfirmationView action,
            ChatPerformance performance) {

        /**
         * 创建开始输出前的元数据事件。
         *
         * @param context 当前请求上下文
         * @param reused 是否复用既有回答
         * @return 元数据事件
         */
        public static ChatEvent meta(
                org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext context,
                boolean reused) {
            return new ChatEvent(
                    EventType.META, context.requestId(), context.conversationId(), context.turnId(),
                    null, null, reused, null, null, null, null);
        }

        /**
         * 创建单个回答增量事件。
         *
         * @param context 当前请求上下文
         * @param delta 增量正文
         * @return 增量事件
         */
        public static ChatEvent delta(
                org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext context,
                String delta) {
            return new ChatEvent(
                    EventType.DELTA, context.requestId(), context.conversationId(), context.turnId(),
                    delta, null, false, null, null, null, null);
        }

        /**
         * 创建要求用户显式确认高风险操作的结构化事件。
         *
         * @param context 当前请求上下文
         * @param action 待确认操作及一次性令牌
         * @return 操作确认事件
         */
        public static ChatEvent actionRequired(
                org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext context,
                ActionConfirmationView action) {
            // 确认令牌只通过服务端结构化事件返回，不写入模型回答正文。
            return new ChatEvent(
                    EventType.ACTION_REQUIRED, context.requestId(), context.conversationId(), context.turnId(),
                    null, null, false, null, null, action, null);
        }

        /**
         * 创建回答完成事件。
         *
         * @param context 当前请求上下文
         * @param content 完整回答
         * @param reused 是否复用既有回答
         * @return 完成事件
         */
        public static ChatEvent done(
                org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext context,
                String content,
                boolean reused) {
            // 幂等重放没有本次流水线的分阶段数据，因此沿用无性能快照的完成事件。
            return done(context, content, reused, null);
        }

        /**
         * 创建携带单次流水线性能快照的回答完成事件。
         *
         * @param context 当前请求上下文
         * @param content 完整回答
         * @param reused 是否复用已有回答
         * @param performance 本轮性能快照
         * @return 携带性能快照的完成事件
         */
        public static ChatEvent done(
                org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext context,
                String content,
                boolean reused,
                ChatPerformance performance) {
            // 性能数据与最终正文在同一个终态事件返回，避免增加新的 SSE 事件顺序。
            return new ChatEvent(
                    EventType.DONE, context.requestId(), context.conversationId(), context.turnId(),
                    null, content, reused, null, null, null, performance);
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
                    EventType.ERROR, command.requestId(), command.conversationId(), null,
                    null, null, false, category, safeMessage, null, null);
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
