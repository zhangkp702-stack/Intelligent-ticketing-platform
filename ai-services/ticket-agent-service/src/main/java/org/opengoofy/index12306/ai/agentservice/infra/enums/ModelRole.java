package org.opengoofy.index12306.ai.agentservice.infra.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 智能体内部不同任务使用的模型角色。
 */
public enum ModelRole {

    /** 根据任务执行结果生成最终用户回答。 */
    ANSWER_SUMMARY(EnumSet.of(ModelCapability.CHAT)),
    /** 拆分用户表达并补全问题上下文。 */
    QUESTION_REWRITE(EnumSet.of(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT)),
    /** 为已解析问题识别意图、槽位和依赖。 */
    TASK_PLANNING(EnumSet.of(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT)),
    /** 压缩已完成会话历史为长期摘要。 */
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
