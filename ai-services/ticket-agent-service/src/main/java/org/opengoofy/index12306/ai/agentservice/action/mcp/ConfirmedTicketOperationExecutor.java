package org.opengoofy.index12306.ai.agentservice.action.mcp;


import org.opengoofy.index12306.ai.agentservice.action.service.ActionStateStore.ClaimedAction;

/**
 * 不暴露给模型、只接受已消费确认令牌草案的取消和退票执行端口。
 */
public interface ConfirmedTicketOperationExecutor {

    /**
     * 根据草案操作类型调用一次受保护的真实取消或退票工具。
     *
     * @param action 已领取执行权的草案快照
     * @param username 当前用户名
     * @return MCP 返回的脱敏业务结果 JSON
     */
    String execute(ClaimedAction action, String username);
}
