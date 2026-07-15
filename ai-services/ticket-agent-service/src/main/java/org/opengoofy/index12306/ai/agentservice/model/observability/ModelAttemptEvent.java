package org.opengoofy.index12306.ai.agentservice.model.observability;

import org.opengoofy.index12306.ai.agentservice.model.config.ModelRole;
import org.opengoofy.index12306.ai.agentservice.model.routing.ModelFailureCategory;

import java.time.Instant;

/**
 * 不包含提示词、响应正文和 API Key 的单次模型尝试审计事件。
 *
 * @param occurredAt 事件发生时间
 * @param role 模型角色
 * @param candidateId 候选项标识
 * @param providerId 平台标识
 * @param modelId 模型 ID
 * @param outcome 尝试结果
 * @param failureCategory 失败分类，成功时为空
 * @param durationMillis 本次尝试耗时
 * @param fallbackIndex 候选项在降级链中的位置
 * @param firstChunkEmitted 流式调用是否已经输出首包
 * @param exceptionType 异常类型名称，不包含异常正文
 */
public record ModelAttemptEvent(
        Instant occurredAt,
        ModelRole role,
        String candidateId,
        String providerId,
        String modelId,
        ModelAttemptOutcome outcome,
        ModelFailureCategory failureCategory,
        long durationMillis,
        int fallbackIndex,
        boolean firstChunkEmitted,
        String exceptionType) {
}
