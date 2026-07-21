package org.opengoofy.index12306.ai.agentservice.infra.model.observability;

import org.opengoofy.index12306.ai.agentservice.infra.chat.ModelClientRegistry;
import org.opengoofy.index12306.ai.agentservice.infra.config.AgentModelProperties;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelRole;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelCircuitState;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.ModelHealthTracker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汇总角色降级链的可用状态，但不把模型故障误判为应用进程故障。
 */
@Component("agentModelRoutingHealthIndicator")
public class ModelRoutingHealthIndicator implements HealthIndicator {

    private final AgentModelProperties properties;
    private final ModelClientRegistry clientRegistry;
    private final ModelHealthTracker healthTracker;

    /**
     * 创建模型路由健康检查器。
     *
     * @param properties 模型角色路由配置
     * @param clientRegistry 已配置客户端注册表
     * @param healthTracker 熔断状态跟踪器
     */
    public ModelRoutingHealthIndicator(
            AgentModelProperties properties,
            ModelClientRegistry clientRegistry,
            ModelHealthTracker healthTracker) {
        this.properties = properties;
        this.clientRegistry = clientRegistry;
        this.healthTracker = healthTracker;
    }

    /**
     * 计算每个角色是否仍有未熔断候选项；缺少密钥时返回 UNKNOWN 而不影响应用启动。
     *
     * @return 不包含 API Key 的健康信息
     */
    @Override
    public Health health() {
        Map<String, Boolean> routeAvailability = new LinkedHashMap<>();
        int availableRoutes = 0;

        // 逐个角色检查是否至少存在一个已配置且未打开熔断器的候选模型。
        for (ModelRole role : ModelRole.values()) {
            boolean available = isRouteAvailable(role);
            routeAvailability.put(role.name(), available);
            if (available) {
                availableRoutes++;
            }
        }

        if (clientRegistry.all().isEmpty()) {
            return Health.status(Status.UNKNOWN)
                    .withDetail("configuredCandidates", 0)
                    .withDetail("reason", "model API keys are not configured")
                    .withDetail("routes", routeAvailability)
                    .build();
        }

        // 只要仍有降级链可用就保持进程健康，并通过 degraded 明确暴露部分不可用状态。
        boolean degraded = availableRoutes < ModelRole.values().length;
        return Health.up()
                .withDetail("configuredCandidates", clientRegistry.all().size())
                .withDetail("availableRoutes", availableRoutes)
                .withDetail("degraded", degraded)
                .withDetail("routes", routeAvailability)
                .build();
    }

    /**
     * 判断指定角色是否存在已配置且未打开熔断器的候选项。
     *
     * @param role 模型角色
     * @return 至少一个候选项可参与调用时返回 {@code true}
     */
    private boolean isRouteAvailable(ModelRole role) {
        List<String> route = properties.routes().get(role);
        for (String candidateId : route) {
            if (clientRegistry.find(candidateId).isPresent()
                    && healthTracker.snapshot(role, candidateId).state() != ModelCircuitState.OPEN) {
                return true;
            }
        }
        return false;
    }
}
