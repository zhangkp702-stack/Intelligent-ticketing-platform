package org.opengoofy.index12306.ai.agentservice.model.routing;

import org.opengoofy.index12306.ai.agentservice.model.config.ModelCapability;
import org.opengoofy.index12306.ai.agentservice.model.config.ModelRole;
import org.opengoofy.index12306.ai.agentservice.model.observability.ModelAttemptContext;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.function.Function;

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
     * 使用显式审计上下文执行同步模型调用。
     *
     * @param role 模型角色
     * @param prompt Spring AI 提示对象
     * @param attemptContext 模型审计关联信息
     * @return 模型响应和最终选模信息
     */
    public ModelCallResult<ChatResponse> call(
            ModelRole role,
            Prompt prompt,
            ModelAttemptContext attemptContext) {
        // 显式传入业务关联字段，避免模型层依赖线程本地上下文。
        return modelRouter.execute(
                role,
                Set.of(),
                attemptContext,
                client -> client.chatModel().call(prompt));
    }

    /**
     * 在每个候选模型的尝试内部完成响应转换，使结构校验失败可以触发下一个模型。
     *
     * @param role 模型角色
     * @param prompt Spring AI 提示对象
     * @param attemptContext 模型审计关联信息
     * @param responseMapper 响应校验及转换逻辑
     * @param <T> 转换后的结果类型
     * @return 转换结果、最终选模信息和成功审计标识
     */
    public <T> ModelCallResult<T> callAndMap(
            ModelRole role,
            Prompt prompt,
            ModelAttemptContext attemptContext,
            Function<ChatResponse, T> responseMapper) {
        // 转换异常发生在单个候选尝试内，路由器可按既有规则继续降级。
        return modelRouter.execute(
                role,
                Set.of(),
                attemptContext,
                client -> responseMapper.apply(client.chatModel().call(prompt)));
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

    /**
     * 使用显式审计上下文创建回答流，并按是否注册工具校验候选模型能力。
     *
     * @param role 模型角色
     * @param prompt Spring AI 提示对象
     * @param attemptContext 模型审计关联信息
     * @param toolsEnabled 本次回答是否允许调用只读工具
     * @return 带首包前模型降级能力的回答流
     */
    public Flux<ChatResponse> stream(
            ModelRole role,
            Prompt prompt,
            ModelAttemptContext attemptContext,
            boolean toolsEnabled) {
        // 工具开启时只选择同时支持流式输出和工具调用的候选模型。
        Set<ModelCapability> capabilities = toolsEnabled
                ? Set.of(ModelCapability.STREAMING, ModelCapability.TOOL_CALLING)
                : Set.of(ModelCapability.STREAMING);
        return modelRouter.stream(
                role,
                capabilities,
                attemptContext,
                client -> client.chatModel().stream(prompt));
    }
}
