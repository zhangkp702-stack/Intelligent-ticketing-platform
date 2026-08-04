package org.opengoofy.index12306.ai.agentservice.action.mq;

import java.time.Instant;

/**
 * @param eventId 数据库 Outbox 事件标识
 * @param actionId 操作草案标识
 * @param eventVersion 事件版本
 * @param createdAt 消息创建时间
 */
public record ActionReconciliationMessage(
        String eventId,
        String actionId,
        long eventVersion,
        Instant createdAt) {
}
