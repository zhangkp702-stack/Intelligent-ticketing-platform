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
 * @param executionOwner 当前轮次执行实例标识
 * @param fencingToken 当前轮次 fencing token
 */
public record AgentRequestContext(
        String requestId,
        String userId,
        String username,
        String conversationId,
        String turnId,
        String executionOwner,
        long fencingToken) {

    /**
     * 兼容不参与轮次执行权校验的查询、测试和历史调用位置。
     *
     * @param requestId 请求幂等标识
     * @param userId 用户标识
     * @param username 用户名
     * @param conversationId 会话标识
     * @param turnId 当前轮次标识
     */
    public AgentRequestContext(
            String requestId,
            String userId,
            String username,
            String conversationId,
            String turnId) {
        this(requestId, userId, username, conversationId, turnId, "unfenced-context", 0L);
    }

    /**
     * 校验请求边界必须提供的身份与会话字段。
     */
    public AgentRequestContext {
        // 业务身份和幂等字段必须显式存在，避免异步线程读取隐式线程状态。
        Assert.hasText(requestId, "请求标识不能为空");
        Assert.hasText(userId, "用户标识不能为空");
        // 用户名是下游既有用户、订单和乘车人服务的归属字段，缺失时不能继续执行查询。
        Assert.hasText(username, "用户名不能为空");
        Assert.hasText(conversationId, "会话标识不能为空");
        Assert.hasText(turnId, "轮次标识不能为空");
        Assert.hasText(executionOwner, "轮次执行实例标识不能为空");
        Assert.isTrue(fencingToken >= 0L, "fencing token 不能为负数");
    }

}
