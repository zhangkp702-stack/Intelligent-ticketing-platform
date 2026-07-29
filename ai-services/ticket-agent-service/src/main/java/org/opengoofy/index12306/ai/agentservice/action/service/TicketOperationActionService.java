package org.opengoofy.index12306.ai.agentservice.action.service;

import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.TicketOperationDraftResult;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;

import java.util.List;

/**
 * 定义取消订单和退票草案的创建、复核及结果转换能力。
 */
public interface TicketOperationActionService {

    /**
     * 读取实时取消条件并创建不可执行的取消订单草案。
     *
     * @param context 已验证的对话上下文
     * @param requestedOrderSn 当前用户订单号
     * @return 安全草案结果
     */
    TicketOperationDraftResult prepareCancellation(
            AgentRequestContext context,
            String requestedOrderSn);

    /**
     * 读取实时可退范围和金额并创建不可执行的退票草案。
     *
     * @param context 已验证的对话上下文
     * @param requestedOrderSn 当前用户订单号
     * @param requestedType 退款类型
     * @param requestedOrderItemIds 部分退款子订单标识
     * @return 安全草案结果
     */
    TicketOperationDraftResult prepareRefund(
            AgentRequestContext context,
            String requestedOrderSn,
            Integer requestedType,
            List<String> requestedOrderItemIds);

    /**
     * 在确认执行前重新校验取消或退票业务快照。
     *
     * @param action 已确认草案
     * @param context 已验证的对话上下文
     */
    void revalidate(ActionDraftEntity action, AgentRequestContext context);

    /**
     * 生成可向用户展示的操作摘要。
     *
     * @param action 操作草案
     * @return 脱敏操作摘要
     */
    String summary(ActionDraftEntity action);

    /**
     * 把持久化结果转换为对应操作结果对象。
     *
     * @param actionType 操作类型
     * @param json 脱敏结果 JSON
     * @return 对应结果对象
     */
    Object readResult(AgentActionType actionType, String json);

    /**
     * 从操作结果中提取稳定业务引用。
     *
     * @param actionType 操作类型
     * @param result 操作结果
     * @return 业务引用
     */
    String resultReference(AgentActionType actionType, Object result);

    /**
     * 返回结果未知时使用的稳定分类。
     *
     * @param actionType 操作类型
     * @return 未知结果分类
     */
    String unknownCategory(AgentActionType actionType);
}
