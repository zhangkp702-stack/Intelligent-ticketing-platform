package org.opengoofy.index12306.ai.agentservice.action.mcp;

import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService.DownstreamResult;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService.WorkItem;

/**
 * 不执行写操作、只查询 ticket-service 权威操作状态的对账端口。
 */
public interface ActionReconciliationProbe {

    /**
     * 使用已持久化草案身份查询同一 actionId 的下游结果。
     *
     * @param workItem 已领取对账事件
     * @return 下游持久化状态和脱敏结果
     */
    DownstreamResult query(WorkItem workItem);
}
