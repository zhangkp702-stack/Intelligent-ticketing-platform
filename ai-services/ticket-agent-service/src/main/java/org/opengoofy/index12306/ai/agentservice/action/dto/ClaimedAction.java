package org.opengoofy.index12306.ai.agentservice.action.dto;

import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;

/**
 * 表示已经完成确认校验并取得执行权的高风险操作快照。
 *
 * @param actionId 草案标识
 * @param executionId 执行记录标识
 * @param requestId 确认请求标识
 * @param actionType 操作类型
 * @param userId 用户标识
 * @param conversationId 会话标识
 * @param turnId 轮次标识
 * @param payloadJson 规范化参数 JSON
 * @param payloadHash 参数指纹
 */
public record ClaimedAction(
        String actionId,
        String executionId,
        String requestId,
        AgentActionType actionType,
        String userId,
        String conversationId,
        String turnId,
        String payloadJson,
        String payloadHash) {
}
