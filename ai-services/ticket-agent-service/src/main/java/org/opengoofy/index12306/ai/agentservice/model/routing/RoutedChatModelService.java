package org.opengoofy.index12306.ai.agentservice.model.routing;

import org.opengoofy.index12306.ai.agentservice.model.config.ModelCapability;
import org.opengoofy.index12306.ai.agentservice.model.config.ModelRole;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Set;

/**
 * 面向后续对话编排层提供按角色路由的 Spring AI 调用入口。
 */
@Service
public class RoutedChatModelService {

    private final ModelRouter modelRouter;

    /**
     * 创建按角色路由的 ChatModel 调用服务。
     *
     * @param modelRouter 模型角色路由器
     */
    public RoutedChatModelService(ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    /**
     * 使用角色默认能力要求执行同步模型调用。
     *
     * @param role 模型角色
     * @param prompt Spring AI 提示对象
     * @return 模型响应和最终选模信息
     */
    public ModelCallResult<ChatResponse> call(ModelRole role, Prompt prompt) {
        return call(role, prompt, Set.of());
    }

    /**
     * 使用角色基础能力和额外能力要求执行同步模型调用。
     *
     * @param role 模型角色
     * @param prompt Spring AI 提示对象
     * @param additionalCapabilities 本次调用额外要求的能力
     * @return 模型响应和最终选模信息
     */
    public ModelCallResult<ChatResponse> call(
            ModelRole role,
            Prompt prompt,
            Set<ModelCapability> additionalCapabilities) {
        // 具体 ChatModel 只在路由器选中候选项后调用，调用方无需持有平台客户端。
        return modelRouter.execute(
                role,
                additionalCapabilities,
                client -> client.chatModel().call(prompt));
    }

    /**
     * 使用角色默认能力要求创建模型响应流，并确保只在首包前自动降级。
     *
     * @param role 模型角色
     * @param prompt Spring AI 提示对象
     * @return 模型响应流
     */
    public Flux<ChatResponse> stream(ModelRole role, Prompt prompt) {
        // 流式入口额外要求候选模型明确声明 STREAMING 能力。
        return modelRouter.stream(
                role,
                Set.of(ModelCapability.STREAMING),
                client -> client.chatModel().stream(prompt));
    }
}
