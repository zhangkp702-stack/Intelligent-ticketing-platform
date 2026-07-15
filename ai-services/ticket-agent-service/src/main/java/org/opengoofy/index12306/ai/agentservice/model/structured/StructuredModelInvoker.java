package org.opengoofy.index12306.ai.agentservice.model.structured;

import org.opengoofy.index12306.ai.agentservice.model.config.ModelRole;
import org.opengoofy.index12306.ai.agentservice.model.observability.ModelAttemptContext;
import org.opengoofy.index12306.ai.agentservice.model.routing.ModelCallResult;
import org.opengoofy.index12306.ai.agentservice.model.routing.RoutedChatModelService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/**
 * 执行带多模型降级和 JSON 结构校验的 Spring AI 调用。
 */
@Service
public class StructuredModelInvoker {

    private final RoutedChatModelService routedChatModelService;
    private final ModelJsonOutputParser outputParser;

    /**
     * 创建结构化模型调用服务。
     *
     * @param routedChatModelService 多模型路由调用入口
     * @param outputParser 模型 JSON 输出解析器
     */
    public StructuredModelInvoker(
            RoutedChatModelService routedChatModelService,
            ModelJsonOutputParser outputParser) {
        this.routedChatModelService = routedChatModelService;
        this.outputParser = outputParser;
    }

    /**
     * 调用指定角色模型，并在单个候选尝试内校验和解析 JSON 结果。
     *
     * @param role 模型角色
     * @param prompt Spring AI 提示对象
     * @param attemptContext 模型审计关联信息
     * @param resultType 结构化结果类型
     * @param <T> 结构化结果类型
     * @return 已解析结果和最终选模元数据
     */
    public <T> ModelCallResult<T> call(
            ModelRole role,
            Prompt prompt,
            ModelAttemptContext attemptContext,
            Class<T> resultType) {
        // 默认调用只执行 JSON 语法和类型校验。
        return call(role, prompt, attemptContext, resultType, Function.identity());
    }

    /**
     * 调用指定角色模型，并在候选尝试内执行 JSON 解析和业务结构校验。
     *
     * @param role 模型角色
     * @param prompt Spring AI 提示对象
     * @param attemptContext 模型审计关联信息
     * @param resultType 结构化结果类型
     * @param validator 结构化结果业务校验器
     * @param <T> 结构化结果类型
     * @return 校验后的结果和最终选模元数据
     */
    public <T> ModelCallResult<T> call(
            ModelRole role,
            Prompt prompt,
            ModelAttemptContext attemptContext,
            Class<T> resultType,
            Function<T, T> validator) {
        // 解析放在路由回调内，格式错误会作为当前候选失败并触发降级。
        return routedChatModelService.callAndMap(
                role,
                prompt,
                attemptContext,
                response -> validator.apply(outputParser.parse(extractText(response), resultType)));
    }

    /**
     * 从 Spring AI 响应中安全提取文本，缺失结果时转为可降级的结构异常。
     *
     * @param response Spring AI 模型响应
     * @return 非空模型文本
     */
    private String extractText(ChatResponse response) {
        // 统一阻止空响应进入业务解析，避免在编排服务中出现空指针异常。
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new InvalidModelOutputException("模型未返回可解析结果");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new InvalidModelOutputException("模型未返回可解析文本");
        }
        return text;
    }
}
