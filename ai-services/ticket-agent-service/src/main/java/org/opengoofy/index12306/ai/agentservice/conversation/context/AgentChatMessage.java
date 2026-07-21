package org.opengoofy.index12306.ai.agentservice.conversation.context;

import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;

import java.util.Objects;

/**
 * 发送给回答模型的一条标准角色消息。
 *
 * @param role 消息角色
 * @param content 消息正文
 */
public record AgentChatMessage(MessageRole role, String content) {

    /**
     * 创建标准角色消息。
     *
     * @param role 消息角色
     * @param content 消息正文
     */
    public AgentChatMessage {
        // 模型上下文不允许出现缺少角色或正文的消息。
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    /**
     * 创建系统消息。
     *
     * @param content 系统规则正文
     * @return SYSTEM 角色消息
     */
    public static AgentChatMessage system(String content) {
        // 系统消息承载身份、规则、摘要或结构化状态。
        return new AgentChatMessage(MessageRole.SYSTEM, content);
    }

    /**
     * 创建用户消息。
     *
     * @param content 用户问题正文
     * @return USER 角色消息
     */
    public static AgentChatMessage user(String content) {
        // 用户消息只承载真实用户输入。
        return new AgentChatMessage(MessageRole.USER, content);
    }

    /**
     * 创建助手消息。
     *
     * @param content 助手回答正文
     * @return ASSISTANT 角色消息
     */
    public static AgentChatMessage assistant(String content) {
        // 助手消息只承载已经完成并持久化的回答。
        return new AgentChatMessage(MessageRole.ASSISTANT, content);
    }
}
