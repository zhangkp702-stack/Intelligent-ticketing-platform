package org.opengoofy.index12306.ai.agentservice.model.client;

import io.micrometer.observation.ObservationRegistry;
import org.opengoofy.index12306.ai.agentservice.model.config.AgentModelProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * 使用 Spring AI 为不同 OpenAI 兼容平台创建相互隔离的模型客户端。
 */
@Component
public class OpenAiCompatibleChatModelFactory {

    private final ObservationRegistry observationRegistry;

    /**
     * 创建模型客户端工厂并接入应用统一观测注册表。
     *
     * @param observationRegistry Micrometer 观测注册表
     */
    public OpenAiCompatibleChatModelFactory(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    /**
     * 根据平台连接信息和候选模型参数创建一个独立的 Spring AI ChatModel。
     *
     * @param providerId 平台配置标识
     * @param candidateId 模型候选项标识
     * @param provider 平台连接配置
     * @param candidate 候选模型配置
     * @return 可直接调用的 Spring AI 模型客户端
     */
    public ChatModel create(
            String providerId,
            String candidateId,
            AgentModelProperties.Provider provider,
            AgentModelProperties.Candidate candidate) {
        // 为同步调用设置明确的连接和读取超时，避免某个平台长期占用调用线程。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(provider.connectTimeout());
        requestFactory.setReadTimeout(provider.readTimeout());
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);

        // 每个平台使用独立 OpenAiApi，API Key 仅保存在内存对象中且不会写入日志。
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(provider.baseUrl().toString())
                .apiKey(provider.apiKey())
                .completionsPath(provider.completionsPath())
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder().filter(ModelHttpCallTimingFilter.create(
                        providerId, candidateId, candidate.model())))
                .build();

        // 关闭模型内部工具执行，由后续智能体编排层控制只读工具和显式确认流程。
        Map<String, Object> extraBody = candidate.extraBody() == null ? Map.of() : candidate.extraBody();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(candidate.model())
                .temperature(candidate.temperature())
                .maxTokens(candidate.maxTokens())
                .extraBody(extraBody)
                .internalToolExecutionEnabled(false)
                .build();

        // 内层只尝试一次，所有跨模型重试和降级统一由外层路由器完成。
        RetryTemplate noRetry = RetryTemplate.builder().maxAttempts(1).build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .retryTemplate(noRetry)
                .observationRegistry(observationRegistry)
                .build();
    }
}
