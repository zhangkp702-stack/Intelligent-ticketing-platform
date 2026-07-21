package org.opengoofy.index12306.ai.agentservice.chat.routing;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.routing.QuestionToolRoutingService.QuestionRoute;
import org.opengoofy.index12306.ai.agentservice.chat.routing.QuestionToolRoutingService.QuestionRoutingDecision;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证普通问答与业务工具路径的本地分流规则。
 */
class QuestionToolRoutingServiceTests {

    private final QuestionToolRoutingService service = new QuestionToolRoutingService();

    /**
     * 验证寒暄和能力介绍不注册 MCP 工具。
     */
    @Test
    void ordinaryQuestionUsesChatOnlyRoute() {
        // 普通问答不含实时车票业务对象，应直接调用回答模型。
        QuestionRoutingDecision decision = service.route("你好，请介绍一下你自己");

        assertThat(decision.route()).isEqualTo(QuestionRoute.CHAT_ONLY);
        assertThat(decision.allowedToolNames()).isEmpty();
    }

    /**
     * 验证余票查询进入业务工具路径。
     */
    @Test
    void ticketQueryUsesToolAssistedRoute() {
        // 查询实时余票必须允许回答模型调用站点解析和车票查询工具。
        QuestionRoutingDecision decision = service.route("查询明天北京到上海的二等座余票");

        assertThat(decision.route()).isEqualTo(QuestionRoute.TOOL_ASSISTED);
        assertThat(decision.allowedToolNames())
                .containsExactlyInAnyOrder("resolve_station", "query_tickets");
    }

    /**
     * 验证只包含车次编号的上下文补全结果仍进入业务工具路径。
     */
    @Test
    void trainCodeUsesToolAssistedRoute() {
        // 车次编号本身属于明确业务实体，不能因为缺少“车票”字样而跳过工具。
        QuestionRoutingDecision decision = service.route("G9003 还有吗");

        assertThat(decision.route()).isEqualTo(QuestionRoute.TOOL_ASSISTED);
        assertThat(decision.allowedToolNames())
                .containsExactlyInAnyOrder("resolve_station", "query_tickets");
    }

    /**
     * 验证购票请求只组合查询、乘车人和购票草案工具。
     */
    @Test
    void purchaseQuestionUsesPurchaseToolGroup() {
        // 购票需要补齐车票和乘车人信息，但不能向模型暴露真实下单工具。
        QuestionRoutingDecision decision =
                service.route("帮我购买明天北京到上海的二等座车票");

        assertThat(decision.route()).isEqualTo(QuestionRoute.TOOL_ASSISTED);
        assertThat(decision.allowedToolNames()).containsExactlyInAnyOrder(
                "resolve_station",
                "query_tickets",
                "list_my_passengers",
                "prepare_ticket_purchase");
    }

    /**
     * 验证退票请求只组合本人订单和退票草案工具。
     */
    @Test
    void refundQuestionUsesRefundToolGroup() {
        // 退票必须先定位本人订单，再生成预览和待确认草案。
        QuestionRoutingDecision decision = service.route("帮我退掉订单 123456");

        assertThat(decision.route()).isEqualTo(QuestionRoute.TOOL_ASSISTED);
        assertThat(decision.allowedToolNames()).containsExactlyInAnyOrder(
                "list_my_orders",
                "get_my_order_detail",
                "preview_ticket_refund",
                "prepare_ticket_refund");
    }
}
