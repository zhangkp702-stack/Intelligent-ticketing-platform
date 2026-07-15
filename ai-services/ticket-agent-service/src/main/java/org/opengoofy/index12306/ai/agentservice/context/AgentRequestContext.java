package org.opengoofy.index12306.ai.agentservice.context;

import org.springframework.util.Assert;

/**
 * 智能体单次请求的不可变业务上下文，显式跨方法传递，不依赖 ThreadLocal。
 *
 * @param requestId 请求幂等标识
 * @param userId 用户标识
 * @param username 用户名
 * @param conversationId 会话标识
 * @param turnId 当前轮次标识
 * @param topicId 当前主题标识，主题路由前可以为空
 */
public record AgentRequestContext(
        String requestId,
        String userId,
        String username,
        String conversationId,
        String turnId,
        String topicId) {

    /**
     * 校验请求边界必须提供的身份与会话字段，主题允许在路由完成后再补充。
     */
    public AgentRequestContext {
        // 业务身份和幂等字段必须显式存在，避免异步线程读取隐式线程状态。
        Assert.hasText(requestId, "请求标识不能为空");
        Assert.hasText(userId, "用户标识不能为空");
        Assert.hasText(conversationId, "会话标识不能为空");
        Assert.hasText(turnId, "轮次标识不能为空");
    }

    /**
     * 返回绑定指定主题的新上下文，保留原请求中的身份、会话和轮次信息。
     *
     * @param selectedTopicId 主题路由选中的主题标识
     * @return 已绑定主题的不可变请求上下文
     */
    public AgentRequestContext withTopicId(String selectedTopicId) {
        // 创建新值而不修改原对象，保证上下文在同步调用链中可以安全共享。
        Assert.hasText(selectedTopicId, "主题标识不能为空");
        return new AgentRequestContext(
                requestId, userId, username, conversationId, turnId, selectedTopicId);
    }
}
