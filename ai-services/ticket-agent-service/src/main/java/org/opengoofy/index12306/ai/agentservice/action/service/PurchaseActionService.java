package org.opengoofy.index12306.ai.agentservice.action.service;

import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ActionStatusView;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ConfirmPurchaseCommand;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchaseDraftResult;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchasePayload;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.RecoverableActionView;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;

import java.util.Optional;

/**
 * 定义需要用户显式确认的购票、取消和退票操作入口。
 */
public interface PurchaseActionService {

    /**
     * 规范化购票参数并在当前轮次创建不可执行草案。
     *
     * @param context 已验证的对话上下文
     * @param requestedPayload 模型生成的购票草案参数
     * @return 不包含确认令牌的安全草案结果
     */
    PurchaseDraftResult prepare(AgentRequestContext context, PurchasePayload requestedPayload);

    /**
     * 查询指定轮次可向用户展示的待确认操作。
     *
     * @param userId 当前用户标识
     * @param turnId 轮次标识
     * @return 待确认操作视图
     */
    Optional<ActionConfirmationView> confirmationForTurn(String userId, String turnId);

    /**
     * 校验确认请求并执行对应的高风险操作。
     *
     * @param command 包含用户、草案、令牌和幂等键的确认命令
     * @return 最新操作状态
     */
    ActionStatusView confirm(ConfirmPurchaseCommand command);

    /**
     * 查询指定操作的最新状态。
     *
     * @param userId 当前用户标识
     * @param actionId 操作草案标识
     * @return 操作状态视图
     */
    ActionStatusView getStatus(String userId, String actionId);

    /**
     * 恢复当前会话最近一个仍需要前端展示的操作。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 可恢复操作视图
     */
    Optional<RecoverableActionView> recoverLatestAction(String userId, String conversationId);
}
