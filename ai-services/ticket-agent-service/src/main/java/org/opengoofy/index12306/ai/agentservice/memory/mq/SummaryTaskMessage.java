package org.opengoofy.index12306.ai.agentservice.memory.mq;

import java.io.Serializable;
import java.time.Instant;

/**
 * 只携带摘要任务定位信息的 RocketMQ 消息，不包含用户对话正文。
 *
 * @param taskId 摘要任务标识
 * @param conversationId 会话标识
 * @param eventVersion 任务事件版本
 * @param throughSequence 目标消息边界
 * @param expectedSummaryVersion 预期摘要版本
 * @param occurredAt 事件创建时间
 */
public record SummaryTaskMessage(
        String taskId,
        String conversationId,
        long eventVersion,
        long throughSequence,
        int expectedSummaryVersion,
        Instant occurredAt) implements Serializable {
}
