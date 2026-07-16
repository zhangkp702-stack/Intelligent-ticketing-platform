package org.opengoofy.index12306.ai.mcpserver.security;

/**
 * MCP 服务验证通过后可向票务业务服务传递的用户与会话身份。
 *
 * @param requestId 请求标识
 * @param userId 用户标识
 * @param username 用户名
 * @param conversationId 会话标识
 * @param turnId 对话轮次标识
 * @param topicId 主题标识
 */
public record McpCallerIdentity(
        String requestId,
        String userId,
        String username,
        String conversationId,
        String turnId,
        String topicId) {
}
