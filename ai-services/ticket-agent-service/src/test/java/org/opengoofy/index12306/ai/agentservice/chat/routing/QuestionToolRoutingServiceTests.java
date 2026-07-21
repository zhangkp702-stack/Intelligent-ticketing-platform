package org.opengoofy.index12306.ai.agentservice.chat.routing;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.routing.QuestionToolRoutingService.BusinessGroup;
import org.opengoofy.index12306.ai.agentservice.chat.routing.QuestionToolRoutingService.QuestionRoute;
import org.opengoofy.index12306.ai.agentservice.chat.routing.QuestionToolRoutingService.QuestionRoutingDecision;
import org.opengoofy.index12306.ai.agentservice.conversation.context.AgentChatMessage;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationHistoryContext;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationTurnContext;

import java.util.List;

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
        assertThat(decision.intent()).isEqualTo(AgentIntent.GENERAL_CHAT);
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
        assertThat(decision.intent()).isEqualTo(AgentIntent.TRAIN_QUERY);
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
        assertThat(decision.intent()).isEqualTo(AgentIntent.TRAIN_QUERY);
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
        assertThat(decision.intent()).isEqualTo(AgentIntent.TICKET_PURCHASE);
        assertThat(decision.allowedToolNames()).containsExactlyInAnyOrder(
                "resolve_station",
                "query_tickets",
                "resolve_purchase_passengers",
                "prepare_ticket_purchase");
    }

    /**
     * 验证取消订单请求只开放服务端订单定位和取消草案工具。
     */
    @Test
    void cancellationQuestionUsesCancellationWorkflowTools() {
        // 模型不能直接读取订单列表后猜测目标，必须经过服务端取消订单解析工具。
        QuestionRoutingDecision decision = service.route("取消我明天 G9003 的未支付订单");

        assertThat(decision.route()).isEqualTo(QuestionRoute.TOOL_ASSISTED);
        assertThat(decision.intent()).isEqualTo(AgentIntent.ORDER_CANCELLATION);
        assertThat(decision.allowedToolNames()).containsExactlyInAnyOrder(
                "resolve_order_cancellation", "prepare_order_cancellation");
    }

    /**
     * 验证退票请求只组合本人订单和退票草案工具。
     */
    @Test
    void refundQuestionUsesRefundToolGroup() {
        // 退票必须先定位本人订单，再生成预览和待确认草案。
        QuestionRoutingDecision decision = service.route("帮我退掉订单 123456");

        assertThat(decision.route()).isEqualTo(QuestionRoute.TOOL_ASSISTED);
        assertThat(decision.intent()).isEqualTo(AgentIntent.TICKET_REFUND);
        assertThat(decision.allowedToolNames()).containsExactlyInAnyOrder(
                "list_my_orders",
                "get_my_order_detail",
                "preview_ticket_refund",
                "prepare_ticket_refund");
    }

    /**
     * 验证购票口语化追问可以根据最近车次上下文获得购票工具组。
     */
    @Test
    void contextualPurchaseFollowupUsesRecentBusinessContext() {
        ConversationHistoryContext history = trainHistory("刚才那个我要了");

        // 当前问题没有明确“购票”字样，但最近一轮已经给出候选车次。
        QuestionRoutingDecision decision = service.route("刚才那个我要了", history);

        assertThat(decision.route()).isEqualTo(QuestionRoute.TOOL_ASSISTED);
        assertThat(decision.matchedGroups()).contains(BusinessGroup.PURCHASE);
        assertThat(decision.allowedToolNames()).contains(
                "resolve_purchase_passengers", "prepare_ticket_purchase");
    }

    /**
     * 验证普通致谢不会因为最近存在车票上下文而误入工具路径。
     */
    @Test
    void ordinaryFollowupDoesNotReuseBusinessTools() {
        ConversationHistoryContext history = trainHistory("谢谢");

        // 只有当前表达包含受控指代或操作信号时才允许使用历史业务兜底。
        QuestionRoutingDecision decision = service.route("谢谢", history);

        assertThat(decision.route()).isEqualTo(QuestionRoute.CHAT_ONLY);
        assertThat(decision.allowedToolNames()).isEmpty();
    }

    /**
     * 创建包含最近车次查询结果的测试会话上下文。
     *
     * @param currentQuestion 当前用户问题
     * @return 可用于上下文兜底的会话历史
     */
    private ConversationHistoryContext trainHistory(String currentQuestion) {
        ConversationTurnContext turn = new ConversationTurnContext(
                "turn-history",
                AgentChatMessage.user("查询明天北京到上海的车票"),
                AgentChatMessage.assistant("第一趟 G9001，第二趟 G9003"));
        // 测试只需要最近完整轮次，摘要边界和快照信息保持为空。
        return new ConversationHistoryContext(
                "conversation-1", null, null, null, null, 0,
                List.of(turn), AgentChatMessage.user(currentQuestion),
                List.of("message-1", "message-2"), 1L, 2L, 20);
    }
}
