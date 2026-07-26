package org.opengoofy.index12306.ai.agentservice.chat.routing;

import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 将已识别的业务意图确定性映射到对应业务链路及其最小工具集。
 */
@Service
public class IntentToolRoutingService {

    private static final Set<String> TRAIN_QUERY_TOOLS = Set.of("resolve_station", "query_tickets");
    private static final Set<String> TRAIN_STOP_TOOLS = Set.of("query_train_stops");
    private static final Set<String> PASSENGER_TOOLS = Set.of("list_my_passengers");
    private static final Set<String> ORDER_QUERY_TOOLS = Set.of("list_my_orders", "get_my_order_detail");
    private static final Set<String> PAYMENT_TOOLS = Set.of("list_my_orders", "get_my_order_detail", "query_pay_status");
    private static final Set<String> PURCHASE_TOOLS = Set.of(
            "resolve_station", "query_tickets", "resolve_purchase_passengers", "prepare_ticket_purchase");
    private static final Set<String> CANCELLATION_TOOLS = Set.of(
            "resolve_order_cancellation", "prepare_order_cancellation");
    private static final Set<String> REFUND_TOOLS = Set.of("resolve_ticket_refund", "prepare_ticket_refund");

    /**
     * 根据小模型给出的受控意图选择唯一业务链路。
     *
     * @param intent 已通过结构化校验的业务意图
     * @return 链路类型、业务组和允许注册的最小工具集合
     */
    public IntentRoutingDecision route(AgentIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("业务意图不能为空");
        }

        // 路由层不再读取或匹配用户文本，只负责稳定的意图到执行链路映射。
        return switch (intent) {
            case GENERAL_CHAT -> IntentRoutingDecision.chatOnly();
            case TRAIN_QUERY -> toolAssisted(intent, BusinessGroup.TRAIN_QUERY, TRAIN_QUERY_TOOLS);
            case TRAIN_STOP_QUERY -> toolAssisted(intent, BusinessGroup.TRAIN_STOP, TRAIN_STOP_TOOLS);
            case PASSENGER_QUERY -> toolAssisted(intent, BusinessGroup.PASSENGER, PASSENGER_TOOLS);
            case ORDER_QUERY -> toolAssisted(intent, BusinessGroup.ORDER_QUERY, ORDER_QUERY_TOOLS);
            case PAYMENT_QUERY -> new IntentRoutingDecision(
                    IntentRoute.TOOL_ASSISTED,
                    intent,
                    Set.of(BusinessGroup.ORDER_QUERY, BusinessGroup.PAYMENT),
                    PAYMENT_TOOLS);
            case TICKET_PURCHASE -> toolAssisted(intent, BusinessGroup.PURCHASE, PURCHASE_TOOLS);
            case ORDER_CANCELLATION -> toolAssisted(intent, BusinessGroup.CANCELLATION, CANCELLATION_TOOLS);
            case TICKET_REFUND -> toolAssisted(intent, BusinessGroup.REFUND, REFUND_TOOLS);
        };
    }

    /**
     * 创建仅包含单个业务组的工具辅助链路。
     *
     * @param intent 当前业务意图
     * @param group 对应业务组
     * @param toolNames 该链路允许使用的工具名称
     * @return 不可变的工具辅助路由结果
     */
    private IntentRoutingDecision toolAssisted(
            AgentIntent intent,
            BusinessGroup group,
            Set<String> toolNames) {
        // 每个写操作意图只开放自身草稿链路，真实写操作仍由确认接口隔离执行。
        return new IntentRoutingDecision(IntentRoute.TOOL_ASSISTED, intent, Set.of(group), Set.copyOf(toolNames));
    }

    /**
     * 回答模型可进入的两种执行路径。
     */
    public enum IntentRoute {
        CHAT_ONLY,
        TOOL_ASSISTED
    }

    /**
     * 可独立执行和观测的票务业务链路组。
     */
    public enum BusinessGroup {
        TRAIN_QUERY,
        TRAIN_STOP,
        PASSENGER,
        ORDER_QUERY,
        PAYMENT,
        PURCHASE,
        CANCELLATION,
        REFUND
    }

    /**
     * 意图模型分类后的确定性链路选择结果。
     *
     * @param route 普通问答或工具辅助路径
     * @param intent 分类模型识别的业务意图
     * @param matchedGroups 当前选择的业务链路组
     * @param allowedToolNames 本轮允许注册给回答模型的工具名称
     */
    public record IntentRoutingDecision(
            IntentRoute route,
            AgentIntent intent,
            Set<BusinessGroup> matchedGroups,
            Set<String> allowedToolNames) {

        /**
         * 创建不携带工具的普通问答结果。
         *
         * @return 普通问答路由结果
         */
        public static IntentRoutingDecision chatOnly() {
            // 普通交流不会向回答模型暴露任何票务工具。
            return new IntentRoutingDecision(
                    IntentRoute.CHAT_ONLY, AgentIntent.GENERAL_CHAT, Set.of(), Set.of());
        }
    }
}
