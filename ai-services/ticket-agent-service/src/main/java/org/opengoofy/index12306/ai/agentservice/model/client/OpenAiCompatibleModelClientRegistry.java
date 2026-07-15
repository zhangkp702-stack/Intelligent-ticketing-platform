package org.opengoofy.index12306.ai.agentservice.model.client;

import org.opengoofy.index12306.ai.agentservice.model.config.AgentModelProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 根据配置注册百炼和 SiliconFlow 的 OpenAI 兼容模型客户端。
 */
@Component
public class OpenAiCompatibleModelClientRegistry implements ModelClientRegistry {

    private final Map<String, RoutedModelClient> clients;

    /**
     * 创建客户端注册表；未提供 API Key 的平台保持未配置状态但不阻止应用启动。
     *
     * @param properties 模型平台和候选项配置
     * @param chatModelFactory OpenAI 兼容模型客户端工厂
     */
    public OpenAiCompatibleModelClientRegistry(
            AgentModelProperties properties,
            OpenAiCompatibleChatModelFactory chatModelFactory) {
        Map<String, RoutedModelClient> registeredClients = new LinkedHashMap<>();

        // 只为启用且密钥完整的平台创建客户端，避免使用空密钥发出无效请求。
        properties.candidates().forEach((candidateId, candidate) -> {
            AgentModelProperties.Provider provider = properties.providers().get(candidate.provider());
            if (!candidate.enabled() || provider == null || !provider.configured()) {
                return;
            }

            // 每个候选项持有独立默认模型参数，确保运行时不会在共享客户端上串改模型。
            RoutedModelClient client = new RoutedModelClient(
                    candidateId,
                    candidate.provider(),
                    candidate.model(),
                    Set.copyOf(candidate.capabilities()),
                    chatModelFactory.create(provider, candidate));
            registeredClients.put(candidateId, client);
        });
        this.clients = Collections.unmodifiableMap(registeredClients);
    }

    /**
     * 根据候选项标识查找已经完成配置的模型客户端。
     *
     * @param candidateId 候选项标识
     * @return 找到时返回模型客户端，否则返回空
     */
    @Override
    public Optional<RoutedModelClient> find(String candidateId) {
        return Optional.ofNullable(clients.get(candidateId));
    }

    /**
     * 返回当前已经注册的全部模型客户端。
     *
     * @return 以候选项标识为键的不可变客户端映射
     */
    @Override
    public Map<String, RoutedModelClient> all() {
        return clients;
    }
}
