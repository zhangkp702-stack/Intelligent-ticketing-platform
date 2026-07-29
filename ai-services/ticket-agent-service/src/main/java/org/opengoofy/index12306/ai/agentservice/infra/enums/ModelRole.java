package org.opengoofy.index12306.ai.agentservice.infra.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 智能体内部不同任务使用的模型角色。
 */
public enum ModelRole {

    ANSWER_SUMMARY(EnumSet.of(ModelCapability.CHAT)),
    TASK_PLANNING(EnumSet.of(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT)),
    MEMORY_SUMMARY(EnumSet.of(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT));

    private final Set<ModelCapability> requiredCapabilities;

    ModelRole(Set<ModelCapability> requiredCapabilities) {
        this.requiredCapabilities = Collections.unmodifiableSet(requiredCapabilities);
    }

    /**
     * 返回该角色正常执行所必需的基础能力集合。
     *
     * @return 不可变的基础能力集合
     */
    public Set<ModelCapability> requiredCapabilities() {
        return requiredCapabilities;
    }
}
