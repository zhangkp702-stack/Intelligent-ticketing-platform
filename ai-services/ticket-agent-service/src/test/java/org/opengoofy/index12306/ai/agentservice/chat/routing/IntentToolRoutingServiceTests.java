package org.opengoofy.index12306.ai.agentservice.chat.routing;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentToolRoutingService.BusinessGroup;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentToolRoutingService.IntentRoute;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentToolRoutingService.IntentRoutingDecision;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证受控业务意图到执行链路和最小工具集的确定性映射。
 */
class IntentToolRoutingServiceTests {

    private final IntentToolRoutingService service = new IntentToolRoutingService();

    /**
     * 验证普通交流不会注册任何票务工具。
     */
    @Test
    void generalChatUsesChatOnlyRoute() {
        // 路由服务只消费已分类枚举，不再读取用户文本或执行关键词匹配。
        IntentRoutingDecision decision = service.route(AgentIntent.GENERAL_CHAT);

        assertThat(decision.route()).isEqualTo(IntentRoute.CHAT_ONLY);
        assertThat(decision.allowedToolNames()).isEmpty();
    }

    /**
     * 验证购票意图进入完整购票草稿链路，但不暴露真实下单工具。
     */
    @Test
    void purchaseIntentUsesPurchaseChain() {
        // 购票链路负责查询车票、解析乘车人并生成待确认草稿。
        IntentRoutingDecision decision = service.route(AgentIntent.TICKET_PURCHASE);

        assertThat(decision.route()).isEqualTo(IntentRoute.TOOL_ASSISTED);
        assertThat(decision.matchedGroups()).containsExactly(BusinessGroup.PURCHASE);
        assertThat(decision.allowedToolNames()).containsExactlyInAnyOrder(
                "resolve_station",
                "query_tickets",
                "resolve_purchase_passengers",
                "prepare_ticket_purchase");
    }

    /**
     * 验证查票、取消订单和退票分别进入互不混用的独立链路。
     */
    @Test
    void businessIntentsUseIndependentChains() {
        // 三类核心业务意图必须映射到各自的最小工具边界。
        assertThat(service.route(AgentIntent.TRAIN_QUERY).allowedToolNames())
                .containsExactlyInAnyOrder("resolve_station", "query_tickets");
        assertThat(service.route(AgentIntent.ORDER_CANCELLATION).allowedToolNames())
                .containsExactlyInAnyOrder("resolve_order_cancellation", "prepare_order_cancellation");
        assertThat(service.route(AgentIntent.TICKET_REFUND).allowedToolNames())
                .containsExactlyInAnyOrder("resolve_ticket_refund", "prepare_ticket_refund");
    }

    /**
     * 验证支付状态链路同时具备订单定位和支付状态查询能力。
     */
    @Test
    void paymentIntentIncludesOrderLookupAndPaymentStatus() {
        // 支付状态必须先定位本人订单，再查询对应支付结果。
        IntentRoutingDecision decision = service.route(AgentIntent.PAYMENT_QUERY);

        assertThat(decision.matchedGroups())
                .containsExactlyInAnyOrder(BusinessGroup.ORDER_QUERY, BusinessGroup.PAYMENT);
        assertThat(decision.allowedToolNames()).containsExactlyInAnyOrder(
                "list_my_orders", "get_my_order_detail", "query_pay_status");
    }
}
