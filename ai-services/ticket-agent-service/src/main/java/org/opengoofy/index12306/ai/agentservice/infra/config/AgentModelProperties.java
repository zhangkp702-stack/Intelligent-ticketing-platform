package org.opengoofy.index12306.ai.agentservice.infra.config;

import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelCapability;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 双平台模型、候选模型和角色降级链配置。
 *
 * @param attemptTimeout 单个候选模型的最大等待时间
 * @param totalTimeout 单次角色路由允许消耗的总时间
 * @param acquireTimeout 等待平台并发许可的最长时间
 * @param failureThreshold 连续失败后打开熔断器的阈值
 * @param openDuration 熔断器打开后的冷却时间
 * @param auditCapacity 内存中保留的最近调用审计数量
 * @param providers 平台连接配置
 * @param candidates 可参与路由的模型候选配置
 * @param routes 各模型角色的有序降级链
 */
@ConfigurationProperties(prefix = "index12306.agent.model")
public record AgentModelProperties(
        @DefaultValue("20s") Duration attemptTimeout,
        @DefaultValue("50s") Duration totalTimeout,
        @DefaultValue("50ms") Duration acquireTimeout,
        @DefaultValue("3") int failureThreshold,
        @DefaultValue("30s") Duration openDuration,
        @DefaultValue("200") int auditCapacity,
        Map<String, Provider> providers,
        Map<String, Candidate> candidates,
        Map<ModelRole, List<String>> routes) {

    /**
     * OpenAI 兼容平台的连接和并发隔离配置。
     *
     * @param enabled 是否允许注册该平台
     * @param baseUrl OpenAI 兼容接口根地址
     * @param apiKey 仅从外部环境注入的平台密钥
     * @param completionsPath Chat Completions 请求路径
     * @param connectTimeout 建立 HTTP 连接的超时时间
     * @param readTimeout 单次 HTTP 响应读取超时时间
     * @param maxConcurrent 该平台允许的最大并发调用数
     */
    public record Provider(
            @DefaultValue("true") boolean enabled,
            URI baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("/v1/chat/completions") String completionsPath,
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("20s") Duration readTimeout,
            @DefaultValue("8") int maxConcurrent) {

        /**
         * 判断平台是否具备创建真实模型客户端的必要配置。
         *
         * @return 平台启用且 API Key 非空时返回 {@code true}
         */
        public boolean configured() {
            return enabled && baseUrl != null && StringUtils.hasText(apiKey);
        }
    }

    /**
     * 一个可以放入角色降级链的具体模型候选项。
     *
     * @param enabled 是否启用该候选项
     * @param provider 所属平台标识
     * @param model 平台要求的精确模型 ID
     * @param capabilities 已确认的模型能力集合
     * @param temperature 默认采样温度
     * @param maxTokens 默认最大输出 Token 数
     * @param extraBody 平台兼容接口需要的额外请求参数
     */
    public record Candidate(
            @DefaultValue("true") boolean enabled,
            String provider,
            String model,
            Set<ModelCapability> capabilities,
            @DefaultValue("0.2") Double temperature,
            @DefaultValue("4096") Integer maxTokens,
            Map<String, Object> extraBody) {
    }
}
