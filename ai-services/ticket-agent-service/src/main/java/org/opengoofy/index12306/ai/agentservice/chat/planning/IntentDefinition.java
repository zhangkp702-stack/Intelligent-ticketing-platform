package org.opengoofy.index12306.ai.agentservice.chat.planning;

import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;

import java.util.List;

/**
 * 描述一个可由任务规划模型选择的稳定业务意图。
 *
 * @param name 服务端受控的意图枚举
 * @param description 面向规划模型的业务边界说明
 * @param examples 应选择当前意图的典型表达
 * @param exclusions 容易混淆但不应选择当前意图的表达
 */
public record IntentDefinition(
        AgentIntent name,
        String description,
        List<String> examples,
        List<String> exclusions) {
}
