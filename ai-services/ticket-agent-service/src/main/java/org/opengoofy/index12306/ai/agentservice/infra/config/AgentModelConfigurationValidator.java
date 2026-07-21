package org.opengoofy.index12306.ai.agentservice.infra.config;

import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelRole;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 在应用启动阶段校验模型平台、候选项和角色路由之间的引用关系。
 */
@Component
public class AgentModelConfigurationValidator implements SmartInitializingSingleton {

    private final AgentModelProperties properties;

    /**
     * 创建模型路由配置校验器。
     *
     * @param properties 待校验的模型配置
     */
    public AgentModelConfigurationValidator(AgentModelProperties properties) {
        this.properties = properties;
    }

    /**
     * 在所有单例创建完成后执行完整配置校验，阻止错误降级链进入运行状态。
     */
    @Override
    public void afterSingletonsInstantiated() {
        // 先校验全局超时、熔断和审计容量，避免运行时出现无效时间预算。
        assertPositive(properties.attemptTimeout(), "单模型超时时间必须大于零");
        assertPositive(properties.totalTimeout(), "路由总超时时间必须大于零");
        assertPositive(properties.acquireTimeout(), "并发许可等待时间必须大于零");
        Assert.isTrue(properties.failureThreshold() > 0, "连续失败阈值必须大于零");
        assertPositive(properties.openDuration(), "熔断冷却时间必须大于零");
        Assert.isTrue(properties.auditCapacity() > 0, "调用审计容量必须大于零");

        // 校验平台连接参数；API Key 允许为空，以支持无密钥的本地启动和测试。
        Assert.notEmpty(properties.providers(), "至少需要配置一个模型平台");
        properties.providers().forEach(this::validateProvider);

        // 校验候选项引用的平台和基础字段，再校验每个角色的完整降级链。
        Assert.notEmpty(properties.candidates(), "至少需要配置一个候选模型");
        properties.candidates().forEach(this::validateCandidate);
        for (ModelRole role : ModelRole.values()) {
            validateRoute(role);
        }
    }

    /**
     * 校验单个平台的地址、超时和并发上限。
     *
     * @param providerId 平台标识
     * @param provider 平台连接配置
     */
    private void validateProvider(String providerId, AgentModelProperties.Provider provider) {
        Assert.isTrue(StringUtils.hasText(providerId), "模型平台标识不能为空");
        Assert.notNull(provider, "模型平台配置不能为空: " + providerId);
        Assert.notNull(provider.baseUrl(), "模型平台地址不能为空: " + providerId);
        Assert.isTrue(StringUtils.hasText(provider.completionsPath()), "模型请求路径不能为空: " + providerId);
        assertPositive(provider.connectTimeout(), "平台连接超时时间必须大于零: " + providerId);
        assertPositive(provider.readTimeout(), "平台读取超时时间必须大于零: " + providerId);
        Assert.isTrue(provider.readTimeout().compareTo(properties.attemptTimeout()) <= 0,
                "平台读取超时时间不能超过单模型超时时间: " + providerId);
        Assert.isTrue(provider.maxConcurrent() > 0, "平台最大并发数必须大于零: " + providerId);
    }

    /**
     * 校验候选模型的平台注册关系、模型 ID 和能力声明。
     *
     * @param candidateId 候选项标识
     * @param candidate 候选模型配置
     */
    private void validateCandidate(String candidateId, AgentModelProperties.Candidate candidate) {
        Assert.isTrue(StringUtils.hasText(candidateId), "候选模型标识不能为空");
        Assert.notNull(candidate, "候选模型配置不能为空: " + candidateId);
        Assert.isTrue(StringUtils.hasText(candidate.provider()), "候选模型平台不能为空: " + candidateId);
        Assert.isTrue(properties.providers().containsKey(candidate.provider()),
                "候选模型引用了不存在的平台: " + candidateId);
        Assert.isTrue(StringUtils.hasText(candidate.model()), "候选模型 ID 不能为空: " + candidateId);
        Assert.notEmpty(candidate.capabilities(), "候选模型能力不能为空: " + candidateId);
        Assert.isTrue(candidate.maxTokens() != null && candidate.maxTokens() > 0,
                "候选模型最大 Token 数必须大于零: " + candidateId);
    }

    /**
     * 校验角色降级链中的候选项存在且具备该角色需要的能力。
     *
     * @param role 待校验的模型角色
     */
    private void validateRoute(ModelRole role) {
        Map<ModelRole, List<String>> routes = properties.routes();
        Assert.notEmpty(routes, "模型角色路由不能为空");
        List<String> route = routes.get(role);
        Assert.notEmpty(route, "模型角色缺少降级链: " + role);

        // 每个候选项都必须存在，并满足角色的基础能力要求。
        for (String candidateId : route) {
            AgentModelProperties.Candidate candidate = properties.candidates().get(candidateId);
            Assert.notNull(candidate, "模型角色引用了不存在的候选项: " + role + " -> " + candidateId);
            Assert.isTrue(candidate.capabilities().containsAll(role.requiredCapabilities()),
                    "候选模型不具备角色所需能力: " + role + " -> " + candidateId);
        }
    }

    /**
     * 校验持续时间不为空且为正数。
     *
     * @param duration 待校验的持续时间
     * @param message 校验失败提示
     */
    private void assertPositive(Duration duration, String message) {
        Assert.isTrue(duration != null && !duration.isZero() && !duration.isNegative(), message);
    }
}
