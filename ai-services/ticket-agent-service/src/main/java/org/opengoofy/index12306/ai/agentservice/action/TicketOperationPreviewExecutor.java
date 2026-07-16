package org.opengoofy.index12306.ai.agentservice.action;

import org.opengoofy.index12306.ai.agentservice.action.TicketOperationActionModels.CancellationPreview;
import org.opengoofy.index12306.ai.agentservice.action.TicketOperationActionModels.RefundPreview;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;

import java.util.List;

/**
 * 通过可信只读 MCP 工具获取取消和退票预览的执行端口。
 */
public interface TicketOperationPreviewExecutor {

    /**
     * 查询当前用户订单的实时取消条件。
     *
     * @param context 已验证的请求上下文
     * @param orderSn 订单号
     * @return 服务端取消预览
     */
    CancellationPreview previewCancellation(AgentRequestContext context, String orderSn);

    /**
     * 查询当前用户指定退票范围的实时金额和车票明细。
     *
     * @param context 已验证的请求上下文
     * @param orderSn 订单号
     * @param type 退款类型
     * @param orderItemIds 部分退款子订单记录标识
     * @return 服务端退票预览
     */
    RefundPreview previewRefund(
            AgentRequestContext context,
            String orderSn,
            Integer type,
            List<String> orderItemIds);
}
