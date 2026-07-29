package org.opengoofy.index12306.ai.agentservice.chat.routing;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentExecutionRouter.BusinessGroup;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentExecutionRouter.IntentExecutionDecision;
import org.opengoofy.index12306.ai.agentservice.chat.routing.IntentExecutionRouter.IntentExecutionRoute;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证受控业务意图到服务端固定执行链的确定性映射。
 */
class IntentExecutionRouterTests {

    private final IntentExecutionRouter service = new IntentExecutionRouter();

    /**
     * 验证普通交流进入无业务分组的问答链。
     */
    @Test
    void generalChatUsesChatOnlyRoute() {
        // 路由服务只消费已校验枚举，不读取用户文本。
        IntentExecutionDecision decision = service.route(AgentIntent.GENERAL_CHAT);

        assertThat(decision.route()).isEqualTo(IntentExecutionRoute.CHAT_ONLY);
        assertThat(decision.matchedGroups()).isEmpty();
    }

    /**
     * 验证查询意图进入服务端只读固定链。
     */
    @Test
    void queryIntentUsesReadOnlyCodeChain() {
        // 查票由服务端固定链直接执行，决策中不再携带模型工具名称。
        IntentExecutionDecision decision = service.route(AgentIntent.TRAIN_QUERY);

        assertThat(decision.route()).isEqualTo(IntentExecutionRoute.READ_ONLY_CODE_CHAIN);
        assertThat(decision.matchedGroups()).containsExactly(BusinessGroup.TRAIN_QUERY);
        assertThat(service.isTransaction(AgentIntent.TRAIN_QUERY)).isFalse();
    }

    /**
     * 验证购票、取消和退票都进入交易固定链。
     */
    @Test
    void transactionIntentsUseTransactionCodeChain() {
        // 交易分类只决定执行链，真实写操作仍需要后续独立确认。
        assertThat(service.isTransaction(AgentIntent.TICKET_PURCHASE)).isTrue();
        assertThat(service.isTransaction(AgentIntent.ORDER_CANCELLATION)).isTrue();
        assertThat(service.isTransaction(AgentIntent.TICKET_REFUND)).isTrue();
        assertThat(service.route(AgentIntent.TICKET_PURCHASE).matchedGroups())
                .containsExactly(BusinessGroup.PURCHASE);
    }

    /**
     * 验证支付查询同时记录订单定位和支付状态两个业务分组。
     */
    @Test
    void paymentQueryIncludesOrderLookupAndPaymentGroups() {
        // 支付状态查询需要先定位订单，再读取支付结果。
        IntentExecutionDecision decision = service.route(AgentIntent.PAYMENT_QUERY);

        assertThat(decision.route()).isEqualTo(IntentExecutionRoute.READ_ONLY_CODE_CHAIN);
        assertThat(decision.matchedGroups())
                .containsExactlyInAnyOrder(BusinessGroup.ORDER_QUERY, BusinessGroup.PAYMENT);
    }
}
