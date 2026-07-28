package org.opengoofy.index12306.ai.agentservice.action.mcp;

import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.action.service.PurchaseActionService;


import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchaseDraftResult;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchasePassenger;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchasePayload;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.service.PurchaseWorkflowService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 供固定购票链生成待确认草案的内部组件，绝不直接创建订单。
 */
@Component
public class PurchaseDraftTools {

    private final PurchaseActionService purchaseActionService;
    private final PurchaseWorkflowService purchaseWorkflowService;

    /**
     * 创建购票草案工具。
     *
     * @param purchaseActionService 购票确认状态机服务
     * @param purchaseWorkflowService 购票工作流阶段服务
     */
    public PurchaseDraftTools(
            PurchaseActionService purchaseActionService,
            PurchaseWorkflowService purchaseWorkflowService) {
        this.purchaseActionService = purchaseActionService;
        this.purchaseWorkflowService = purchaseWorkflowService;
    }

    /**
     * 根据用户已经明确的车次、区间和乘车人生成待确认草案。
     *
     * @param trainId query_tickets 返回的车次内部标识
     * @param departure 出发站名称
     * @param arrival 到达站名称
     * @param departureDate 用户选择的乘车日期
     * @param passengers 乘车人和席别列表
     * @param chooseSeats 可选座位偏好
     * @param toolContext 服务端注入的用户和轮次上下文
     * @return 不含确认令牌的草案摘要
     */
    public PurchaseDraftResult prepareTicketPurchase(
            String trainId,
            String departure,
            String arrival,
            String departureDate,
            List<PassengerDraftInput> passengers,
            List<String> chooseSeats,
            ToolContext toolContext) {
        AgentRequestContext context = requestContext(toolContext);
        // 保留空条目交给统一草案校验处理，避免本地工具抛出无分类的空指针异常。
        List<PurchasePassenger> normalizedPassengers = passengers == null
                ? List.of() : passengers.stream()
                .map(passenger -> passenger == null
                        ? null : new PurchasePassenger(
                                passenger.passengerId(),
                                passenger.seatClass() == null ? null : passenger.seatClass().code()))
                .toList();

        // 草案只能使用当前购票工作流已经确认的行程和乘车人，模型参数不能覆盖服务端状态。
        purchaseWorkflowService.validateDraft(
                context.userId(),
                context.conversationId(),
                trainId,
                departure,
                arrival,
                departureDate,
                normalizedPassengers.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(PurchasePassenger::passengerId)
                        .toList());

        // 本地工具只持久化草案，真实购票必须由独立确认接口继续执行。
        PurchaseDraftResult result = purchaseActionService.prepare(
                context,
                new PurchasePayload(
                        trainId, departure, arrival, departureDate, normalizedPassengers, chooseSeats));
        // 草案已经持久化后再结束购票链路，真实下单仍必须等待独立确认接口。
        purchaseWorkflowService.completeAfterDraft(context.userId(), context.conversationId());
        return result;
    }

    /**
     * 从模型不可修改的工具上下文恢复当前请求身份。
     *
     * @param toolContext Spring AI 工具上下文
     * @return 显式请求上下文
     */
    private AgentRequestContext requestContext(ToolContext toolContext) {
        Map<String, Object> values = toolContext == null ? Map.of() : toolContext.getContext();
        // 所有必填字段继续由 AgentRequestContext 做非空校验，禁止匿名生成草案。
        return new AgentRequestContext(
                text(values, McpToolContextFactory.REQUEST_ID),
                text(values, McpToolContextFactory.USER_ID),
                text(values, McpToolContextFactory.USERNAME),
                text(values, McpToolContextFactory.CONVERSATION_ID),
                text(values, McpToolContextFactory.TURN_ID));
    }

    /**
     * 从工具上下文读取文本字段。
     *
     * @param values 工具上下文属性
     * @param key 字段名
     * @return 字段文本或 null
     */
    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * @param passengerId 固定购票链定向查询并匹配后返回的乘车人 ID
     * @param seatClass 语义化席别，由服务端转换为票务编码
     */
    public record PassengerDraftInput(String passengerId, PurchaseSeatClass seatClass) {
    }
}
