package org.opengoofy.index12306.ai.agentservice.infra.chat;

import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelCapability;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Set;

/**
 * 已创建并可以参与角色路由的模型客户端。
 *
 * @param candidateId 候选项标识
 * @param providerId 平台标识
 * @param modelId 平台模型 ID
 * @param capabilities 模型能力集合
 * @param chatModel Spring AI 模型客户端
 */
public record RoutedModelClient(
        String candidateId,
        String providerId,
        String modelId,
        Set<ModelCapability> capabilities,
        ChatModel chatModel) {
}
