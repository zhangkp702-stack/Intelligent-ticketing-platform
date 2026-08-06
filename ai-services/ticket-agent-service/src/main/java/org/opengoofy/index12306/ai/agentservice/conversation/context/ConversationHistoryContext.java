package org.opengoofy.index12306.ai.agentservice.conversation.context;

import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;

import java.util.List;
import java.util.Objects;

/**
 * 会话摘要与最近终态轮次组成的历史上下文。
 *
 * @param conversationId 会话标识
 * @param summaryId 摘要标识
 * @param summaryContent 会话摘要正文
 * @param structuredState 结构化业务状态
 * @param summaryVersion 摘要版本
 * @param summarizedThroughSequence 摘要覆盖到的消息序号
 * @param recentTurns 最近终态历史轮次；失败或取消轮次可能没有助手回答
 * @param currentQuestion 当前用户问题
 * @param messageIds 实际进入历史上下文的消息标识
 * @param fromSequence 历史消息起始序号
 * @param throughSequence 历史消息结束序号
 */
public record ConversationHistoryContext(
        String conversationId,
        String summaryId,
        String summaryContent,
        String structuredState,
        Integer summaryVersion,
        long summarizedThroughSequence,
        List<ConversationTurnContext> recentTurns,
        AgentChatMessage currentQuestion,
        List<String> messageIds,
        Long fromSequence,
        Long throughSequence) {

    /**
     * 规范化会话历史集合。
     *
     * @param conversationId 会话标识
     * @param summaryId 摘要标识
     * @param summaryContent 会话摘要正文
     * @param structuredState 结构化业务状态
     * @param summaryVersion 摘要版本
     * @param summarizedThroughSequence 摘要覆盖到的消息序号
     * @param recentTurns 最近终态历史轮次；失败或取消轮次可能没有助手回答
     * @param currentQuestion 当前用户问题
     * @param messageIds 实际使用的消息标识
     * @param fromSequence 历史起始序号
     * @param throughSequence 历史结束序号
     */
    public ConversationHistoryContext {
        // 对外暴露不可变集合，避免 Pipeline 阶段意外修改已经审计的上下文。
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
        Objects.requireNonNull(currentQuestion, "currentQuestion");
        if (currentQuestion.role() != MessageRole.USER) {
            throw new IllegalArgumentException("当前问题角色必须为 USER");
        }
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }
}
