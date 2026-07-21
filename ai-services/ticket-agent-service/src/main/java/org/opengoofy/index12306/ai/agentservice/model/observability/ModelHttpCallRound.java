package org.opengoofy.index12306.ai.agentservice.model.observability;

/**
 * 不包含提示词和响应正文的单次真实模型 HTTP 调用耗时。
 *
 * @param round 当前回答请求内的模型调用轮次
 * @param providerId 模型平台标识
 * @param candidateId 路由候选项标识
 * @param modelId 平台模型标识
 * @param outcome 调用结果
 * @param firstChunkMillis 首个响应数据块耗时，未收到首包时为 -1
 * @param durationMillis 完整 HTTP 响应流耗时
 * @param httpStatus HTTP 状态码，未收到响应头时为 -1
 */
public record ModelHttpCallRound(
        long round,
        String providerId,
        String candidateId,
        String modelId,
        String outcome,
        long firstChunkMillis,
        long durationMillis,
        int httpStatus) {
}
