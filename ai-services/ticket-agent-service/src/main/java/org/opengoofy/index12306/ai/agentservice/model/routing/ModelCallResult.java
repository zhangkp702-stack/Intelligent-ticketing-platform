package org.opengoofy.index12306.ai.agentservice.model.routing;

import java.time.Duration;

/**
 * 同步模型路由成功后的结果和选模元数据。
 *
 * @param value 模型返回值
 * @param candidateId 最终选中的候选项标识
 * @param providerId 最终选中的平台标识
 * @param modelId 最终选中的模型 ID
 * @param fallbackIndex 候选项在降级链中的位置
 * @param elapsed 整个路由过程耗时
 * @param <T> 模型返回值类型
 * @param modelCallId 成功模型尝试的持久化审计标识
 */
public record ModelCallResult<T>(
        T value,
        String candidateId,
        String providerId,
        String modelId,
        int fallbackIndex,
        Duration elapsed,
        String modelCallId) {
}
