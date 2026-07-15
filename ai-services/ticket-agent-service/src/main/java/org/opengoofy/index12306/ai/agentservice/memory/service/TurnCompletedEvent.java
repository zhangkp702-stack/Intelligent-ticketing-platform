package org.opengoofy.index12306.ai.agentservice.memory.service;

/**
 * 回答事务完成后触发摘要检查所需的最小业务事件。
 *
 * @param userId 会话所属用户标识
 * @param conversationId 会话标识
 * @param topicId 主题标识
 * @param throughSequence 本次允许摘要覆盖到的消息序号
 */
public record TurnCompletedEvent(
        String userId,
        String conversationId,
        String topicId,
        long throughSequence) {
}
