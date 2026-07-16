package org.opengoofy.index12306.ai.agentservice.action;

import org.opengoofy.index12306.ai.agentservice.action.ActionStateStore.ClaimedAction;

/**
 * 不暴露给模型、只接受已消费确认令牌草案的购票执行端口。
 */
public interface ConfirmedPurchaseExecutor {

    /**
     * 通过受保护的 MCP 写工具执行一次真实购票。
     *
     * @param action 已领取执行权的草案快照
     * @param username 当前用户名
     * @return MCP 返回的脱敏购票结果 JSON
     */
    String execute(ClaimedAction action, String username);
}
