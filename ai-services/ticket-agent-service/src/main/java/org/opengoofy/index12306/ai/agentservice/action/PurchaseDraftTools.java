package org.opengoofy.index12306.ai.agentservice.action;

import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchaseDraftResult;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchasePassenger;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchasePayload;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 允许模型生成购票草案但绝不直接创建订单的本地 Spring AI 工具。
 */
@Component
public class PurchaseDraftTools {

    private final PurchaseActionService purchaseActionService;
    private final ActionDraftCreationTracker actionDraftCreationTracker;

    /**
     * 创建购票草案工具。
     *
     * @param purchaseActionService 购票确认状态机服务
     * @param actionDraftCreationTracker 本轮草案创建信号
     */
    public PurchaseDraftTools(
            PurchaseActionService purchaseActionService,
            ActionDraftCreationTracker actionDraftCreationTracker) {
        this.purchaseActionService = purchaseActionService;
        this.actionDraftCreationTracker = actionDraftCreationTracker;
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
    @Tool(
            name = "prepare_ticket_purchase",
            description = "仅生成待用户确认的购票草案，不会创建订单。只有车次、区间、乘车人和席别都已明确时才能调用。")
    public PurchaseDraftResult prepareTicketPurchase(
            @ToolParam(description = "query_tickets 返回的 trainId") String trainId,
            @ToolParam(description = "出发站完整名称") String departure,
            @ToolParam(description = "到达站完整名称") String arrival,
            @ToolParam(description = "query_tickets 使用的乘车日期，严格使用 yyyy-MM-dd 格式") String departureDate,
            @ToolParam(description = "乘车人 ID 与语义席别列表；seatClass 必须使用枚举名称，例如 FIRST_CLASS 表示一等座")
            List<PassengerDraftInput> passengers,
            @ToolParam(required = false, description = "可选座位偏好，如 3A、3B") List<String> chooseSeats,
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

        // 本地工具只持久化草案，真实购票必须由独立确认接口继续执行。
        PurchaseDraftResult result = purchaseActionService.prepare(
                context,
                new PurchasePayload(
                        trainId, departure, arrival, departureDate, normalizedPassengers, chooseSeats));
        // 仅在数据库草案创建或复用成功后标记本轮，供对话完成阶段按需读取确认视图。
        actionDraftCreationTracker.markCreated(context.turnId());
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
     * @param passengerId list_my_passengers 返回的乘车人 ID
     * @param seatClass 语义化席别，由服务端转换为票务编码
     */
    public record PassengerDraftInput(String passengerId, PurchaseSeatClass seatClass) {
    }
}
