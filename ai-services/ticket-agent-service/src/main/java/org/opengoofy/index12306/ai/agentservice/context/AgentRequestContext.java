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
 */
public record AgentRequestContext(
        String requestId,
        String userId,
        String username,
        String conversationId,
        String turnId) {

    /**
     * 校验请求边界必须提供的身份与会话字段。
     */
    public AgentRequestContext {
        // 业务身份和幂等字段必须显式存在，避免异步线程读取隐式线程状态。
        Assert.hasText(requestId, "请求标识不能为空");
        Assert.hasText(userId, "用户标识不能为空");
        Assert.hasText(conversationId, "会话标识不能为空");
        Assert.hasText(turnId, "轮次标识不能为空");
    }

}
