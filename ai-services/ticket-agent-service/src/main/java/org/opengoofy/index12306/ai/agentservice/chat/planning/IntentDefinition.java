package org.opengoofy.index12306.ai.agentservice.chat.planning;

import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;

/**
 * 描述一个可由任务规划模型选择的稳定业务意图。
 *
 * @param name 服务端受控的意图枚举
 * @param description 面向规划模型的业务边界说明
 */
public record IntentDefinition(
        AgentIntent name,
        String description) {
}
