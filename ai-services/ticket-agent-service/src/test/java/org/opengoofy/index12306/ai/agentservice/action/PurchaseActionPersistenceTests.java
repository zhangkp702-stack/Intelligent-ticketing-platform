package org.opengoofy.index12306.ai.agentservice.action;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.opengoofy.index12306.ai.agentservice.action.mcp.ConfirmedPurchaseExecutor;
import org.opengoofy.index12306.ai.agentservice.action.mcp.ConfirmedTicketOperationExecutor;
import org.opengoofy.index12306.ai.agentservice.action.mcp.TicketOperationPreviewExecutor;
import org.opengoofy.index12306.ai.agentservice.action.service.PurchaseActionService;
import org.opengoofy.index12306.ai.agentservice.action.service.PurchaseDraftRevalidationService;
import org.opengoofy.index12306.ai.agentservice.action.service.TicketOperationActionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ActionStatusView;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ConfirmPurchaseCommand;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchasePassenger;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchasePayload;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.CancellationPreview;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.RefundPreview;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.RefundableTicket;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.chat.exception.AgentChatException;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证购票草案、一次性确认和持久化幂等状态机的核心边界。
 */
@ActiveProfiles("test")
@SpringBootTest
@Import(PurchaseActionPersistenceTests.ExecutorConfiguration.class)
class PurchaseActionPersistenceTests {

    @Autowired
    private PurchaseActionService purchaseActionService;

    @Autowired
    private TicketOperationActionService ticketOperationActionService;

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private ConfirmedPurchaseExecutor executor;

    @Autowired
    private ConfirmedTicketOperationExecutor ticketOperationExecutor;

    @Autowired
    private TicketOperationPreviewExecutor previewExecutor;

    @Autowired
    private PurchaseDraftRevalidationService purchaseDraftRevalidationService;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * 清理共享 Spring 测试上下文中的执行器调用记录。
     */
    @BeforeEach
    void resetExecutor() {
        // 每个测试独立断言真实购票执行次数，避免上下文缓存造成相互影响。
        reset(executor, ticketOperationExecutor, previewExecutor, purchaseDraftRevalidationService);
    }

    /**
     * 验证合法令牌只执行一次真实购票，重复确认直接复用持久化成功结果。
     */
    @Test
    void confirmedPurchaseExecutesOnceAndReusesStoredResult() {
        Fixture fixture = createRunningTurn();
        double executionsBefore = counterValue(
                "agent.action.executions", "TICKET_PURCHASE", "outcome", "SUCCEEDED");
        when(executor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("alice")))
                .thenReturn("{\"orderSn\":\"order-1001\",\"tickets\":[]}");

        // 模型阶段只生成草案，确认令牌由回答完成后的服务端视图签发。
        purchaseActionService.prepare(fixture.context(), payload());
        ActionConfirmationView confirmation = purchaseActionService
                .confirmationForTurn(fixture.userId(), fixture.turnId())
                .orElseThrow();
        ConfirmPurchaseCommand command = new ConfirmPurchaseCommand(
                unique("confirm"), unique("idempotency"), fixture.userId(), "alice",
                confirmation.actionId(), confirmation.confirmationToken());

        // 首次确认执行 MCP，第二次相同确认只读取已经落库的脱敏结果。
        ActionStatusView first = purchaseActionService.confirm(command);
        ActionStatusView retried = purchaseActionService.confirm(command);
        assertThat(first.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        assertThat(first.orderSn()).isEqualTo("order-1001");
        assertThat(retried).isEqualTo(first);
        assertThat(counterValue(
                "agent.action.executions", "TICKET_PURCHASE", "outcome", "SUCCEEDED"))
                .isEqualTo(executionsBefore + 1);
        verify(executor, times(1)).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("alice"));
        verify(purchaseDraftRevalidationService, times(1)).revalidate(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 验证伪造确认令牌不能领取执行权，也不会调用真实购票执行器。
     */
    @Test
    void invalidConfirmationTokenDoesNotExecutePurchase() {
        Fixture fixture = createRunningTurn();
        purchaseActionService.prepare(fixture.context(), payload());
        ActionConfirmationView confirmation = purchaseActionService
                .confirmationForTurn(fixture.userId(), fixture.turnId())
                .orElseThrow();

        // 错误令牌在数据库行锁事务内被拒绝，草案继续保持等待确认状态。
        ConfirmPurchaseCommand command = new ConfirmPurchaseCommand(
                unique("confirm"), unique("idempotency"), fixture.userId(), "alice",
                confirmation.actionId(), "invalid-token");
        assertThatThrownBy(() -> purchaseActionService.confirm(command))
                .hasMessageContaining("确认令牌无效");
        assertThat(purchaseActionService.getStatus(fixture.userId(), confirmation.actionId()).status())
                .isEqualTo(AgentActionStatus.AWAITING_CONFIRMATION);
        verifyNoInteractions(executor);
        verifyNoInteractions(purchaseDraftRevalidationService);
    }

    /**
     * 验证确认前余票发生变化时不消费令牌，也不调用真实购票执行器。
     */
    @Test
    void changedPurchaseInventoryDoesNotConsumeConfirmation() {
        Fixture fixture = createRunningTurn();
        double rejectionsBefore = counterValue(
                "agent.action.confirmation.rejections",
                "TICKET_PURCHASE",
                "reason",
                "PREVIEW_CHANGED");
        purchaseActionService.prepare(fixture.context(), payload());
        ActionConfirmationView confirmation = purchaseActionService
                .confirmationForTurn(fixture.userId(), fixture.turnId())
                .orElseThrow();
        doThrow(new AgentChatException(
                HttpStatus.CONFLICT,
                "PURCHASE_DRAFT_STALE",
                "车次或余票状态已经变化，请重新查询并生成购票草案"))
                .when(purchaseDraftRevalidationService)
                .revalidate(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        ConfirmPurchaseCommand command = new ConfirmPurchaseCommand(
                unique("confirm"), unique("idempotency"), fixture.userId(), "alice",
                confirmation.actionId(), confirmation.confirmationToken());

        // 重新核验失败发生在数据库领取执行权之前，原草案仍保持等待确认。
        assertThatThrownBy(() -> purchaseActionService.confirm(command))
                .hasMessageContaining("余票状态已经变化");
        assertThat(purchaseActionService.getStatus(fixture.userId(), confirmation.actionId()).status())
                .isEqualTo(AgentActionStatus.AWAITING_CONFIRMATION);
        assertThat(counterValue(
                "agent.action.confirmation.rejections",
                "TICKET_PURCHASE",
                "reason",
                "PREVIEW_CHANGED"))
                .isEqualTo(rejectionsBefore + 1);
        verifyNoInteractions(executor);
    }

    /**
     * 验证 MCP 明确返回工具拒绝时记录 FAILED，而不是误报为结果待核对。
     */
    @Test
    void explicitPurchaseRejectionIsRecordedAsFailed() {
        Fixture fixture = createRunningTurn();
        ToolExecutionException rejection = mock(ToolExecutionException.class);
        when(rejection.getMessage()).thenReturn("MCP error: PURCHASE_REJECTED: insufficient tickets");
        when(executor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("alice")))
                .thenThrow(rejection);

        // 先创建并签发正常草案，再模拟 MCP 返回明确工具错误。
        purchaseActionService.prepare(fixture.context(), payload());
        ActionConfirmationView confirmation = purchaseActionService
                .confirmationForTurn(fixture.userId(), fixture.turnId())
                .orElseThrow();
        ConfirmPurchaseCommand command = new ConfirmPurchaseCommand(
                unique("confirm"), unique("idempotency"), fixture.userId(), "alice",
                confirmation.actionId(), confirmation.confirmationToken());

        assertThatThrownBy(() -> purchaseActionService.confirm(command))
                .hasMessageContaining("购票未成功");
        ActionStatusView status = purchaseActionService.getStatus(fixture.userId(), confirmation.actionId());
        assertThat(status.status()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(status.failureCategory()).isEqualTo("PURCHASE_REJECTED");
    }

    /**
     * 验证网络超时仍记录 UNKNOWN，避免下游已经创建订单时允许用户重复提交。
     */
    @Test
    void purchaseTimeoutRemainsUnknown() {
        Fixture fixture = createRunningTurn();
        double unknownBefore = counterValue(
                "agent.action.executions", "TICKET_PURCHASE", "outcome", "UNKNOWN");
        when(executor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("alice")))
                .thenThrow(new IllegalStateException("Ticket service read timed out"));

        // 超时发生时无法证明订单未创建，因此状态必须继续要求人工核对。
        purchaseActionService.prepare(fixture.context(), payload());
        ActionConfirmationView confirmation = purchaseActionService
                .confirmationForTurn(fixture.userId(), fixture.turnId())
                .orElseThrow();
        ConfirmPurchaseCommand command = new ConfirmPurchaseCommand(
                unique("confirm"), unique("idempotency"), fixture.userId(), "alice",
                confirmation.actionId(), confirmation.confirmationToken());

        assertThatThrownBy(() -> purchaseActionService.confirm(command))
                .hasMessageContaining("无法确认");
        ActionStatusView status = purchaseActionService.getStatus(fixture.userId(), confirmation.actionId());
        assertThat(status.status()).isEqualTo(AgentActionStatus.UNKNOWN);
        assertThat(status.failureCategory()).isEqualTo("PURCHASE_RESULT_UNKNOWN");
        assertThat(counterValue(
                "agent.action.executions", "TICKET_PURCHASE", "outcome", "UNKNOWN"))
                .isEqualTo(unknownBefore + 1);
    }

    /**
     * 验证确认摘要展示语义席别和内部编码，帮助用户在下单前发现映射错误。
     */
    @Test
    void purchaseSummaryShowsSeatLabelAndCode() {
        Fixture fixture = createRunningTurn();

        // 一等座必须稳定映射为编码 1，并在确认卡片同时展示两种信息。
        purchaseActionService.prepare(
                fixture.context(),
                new PurchasePayload(
                        "train-100", "北京南", "上海虹桥", "2099-01-01",
                        List.of(new PurchasePassenger("passenger-1", 1)), List.of()));
        ActionConfirmationView confirmation = purchaseActionService
                .confirmationForTurn(fixture.userId(), fixture.turnId())
                .orElseThrow();

        assertThat(confirmation.summary())
                .contains("乘车日期 2099-01-01")
                .contains("一等座（编码 1）");
    }

    /**
     * 验证取消订单必须经过可信预览和显式确认，重复确认不会再次调用真实写工具。
     */
    @Test
    void confirmedCancellationExecutesOnceAndReusesStoredResult() {
        Fixture fixture = createRunningTurn();
        CancellationPreview preview = new CancellationPreview(
                "order-2001", 10, true, false, false, null);
        when(previewExecutor.previewCancellation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("order-2001")))
                .thenReturn(preview);
        when(ticketOperationExecutor.execute(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("alice")))
                .thenReturn("{\"orderSn\":\"order-2001\",\"cancelled\":true}");

        // 创建草案和确认前重检都使用同一可信业务状态。
        ticketOperationActionService.prepareCancellation(fixture.context(), "order-2001");
        ActionConfirmationView confirmation = purchaseActionService
                .confirmationForTurn(fixture.userId(), fixture.turnId())
                .orElseThrow();
        ConfirmPurchaseCommand command = new ConfirmPurchaseCommand(
                unique("confirm"), unique("idempotency"), fixture.userId(), "alice",
                confirmation.actionId(), confirmation.confirmationToken());

        // 首次确认执行真实取消，重复确认直接读取持久化结果。
        ActionStatusView first = purchaseActionService.confirm(command);
        ActionStatusView retried = purchaseActionService.confirm(command);
        assertThat(first.actionType()).isEqualTo("TICKET_CANCEL");
        assertThat(first.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        assertThat(first.orderSn()).isEqualTo("order-2001");
        assertThat(retried).isEqualTo(first);
        verify(ticketOperationExecutor, times(1)).execute(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("alice"));
    }

    /**
     * 验证退票金额或范围在确认前变化时拒绝执行，并保留原草案等待重新生成。
     */
    @Test
    void changedRefundPreviewDoesNotConsumeConfirmation() {
        Fixture fixture = createRunningTurn();
        RefundableTicket ticket = new RefundableTicket(
                "item-1", "张三", 3, "02", "01A", 20, 5000);
        RefundPreview initial = new RefundPreview(
                "order-3001", 0, true, 5000, List.of(ticket), null);
        RefundPreview changed = new RefundPreview(
                "order-3001", 0, true, 4500,
                List.of(new RefundableTicket(
                        "item-1", "张三", 3, "02", "01A", 20, 4500)),
                null);
        when(previewExecutor.previewRefund(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("order-3001"),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(List.of("item-1"))))
                .thenReturn(initial, changed);

        // 草案保存首次预览金额，确认时重新预览发现变化后拒绝消费令牌。
        ticketOperationActionService.prepareRefund(
                fixture.context(), "order-3001", 0, List.of("item-1"));
        ActionConfirmationView confirmation = purchaseActionService
                .confirmationForTurn(fixture.userId(), fixture.turnId())
                .orElseThrow();
        assertThat(confirmation.summary()).contains("预计退款金额 50.00 元");
        ConfirmPurchaseCommand command = new ConfirmPurchaseCommand(
                unique("confirm"), unique("idempotency"), fixture.userId(), "alice",
                confirmation.actionId(), confirmation.confirmationToken());

        assertThatThrownBy(() -> purchaseActionService.confirm(command))
                .hasMessageContaining("已经变化");
        assertThat(purchaseActionService.getStatus(fixture.userId(), confirmation.actionId()).status())
                .isEqualTo(AgentActionStatus.AWAITING_CONFIRMATION);
        verifyNoInteractions(ticketOperationExecutor);
    }

    /**
     * 创建已经绑定主题但仍处于运行中的测试轮次。
     *
     * @return 可用于本地草案工具的请求上下文和标识
     */
    private Fixture createRunningTurn() {
        String userId = unique("user");
        String requestId = unique("request");
        ConversationEntity conversation = conversationMemoryService.createConversation(userId, "购票确认测试");
        // 草案只能在当前运行轮次内创建，因此先启动可信轮次。
        ConversationMemoryService.StartedTurn turn = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), requestId, requestId, "购买测试车票", 5));
        AgentRequestContext context = new AgentRequestContext(
                requestId, userId, "alice", conversation.getId(), turn.turnId());
        return new Fixture(userId, turn.turnId(), context);
    }

    /**
     * 创建稳定的购票草案参数。
     *
     * @return 包含一个乘车人的购票参数
     */
    private PurchasePayload payload() {
        return new PurchasePayload(
                "train-100", "北京南", "上海虹桥", "2099-01-01",
                List.of(new PurchasePassenger("passenger-1", 3)), List.of("01A"));
    }

    /**
     * 生成满足数据库字段长度限制的唯一测试值。
     *
     * @param prefix 可读前缀
     * @return 唯一文本
     */
    private String unique(String prefix) {
        // UUID 去除分隔符后可直接用于请求标识和幂等键。
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 读取指定操作类型和终态标签的当前计数。
     *
     * @param metricName 指标名称
     * @param actionType 操作类型
     * @param outcomeTag 结果标签名称
     * @param outcomeValue 结果标签值
     * @return 指标尚未创建时返回 0，否则返回当前累计值
     */
    private double counterValue(
            String metricName,
            String actionType,
            String outcomeTag,
            String outcomeValue) {
        // 测试先读取基线再执行操作，避免共享 Spring 上下文中的其他用例影响增量断言。
        Counter counter = meterRegistry.find(metricName)
                .tags("actionType", actionType, outcomeTag, outcomeValue)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /**
     * 为测试上下文提供不会连接真实 MCP 服务的购票执行器替身。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ExecutorConfiguration {

        /**
         * 创建可在测试中验证调用次数和返回结果的执行器替身。
         *
         * @return Mockito 购票执行器
         */
        @Bean
        ConfirmedPurchaseExecutor confirmedPurchaseExecutor() {
            // 测试只验证确认状态机，不发起任何真实下单请求。
            return mock(ConfirmedPurchaseExecutor.class);
        }

        /**
         * 创建可验证取消和退票执行次数的写执行器替身。
         *
         * @return Mockito 订单操作执行器
         */
        @Bean
        ConfirmedTicketOperationExecutor confirmedTicketOperationExecutor() {
            // 测试只验证 Agent 状态机，不访问真实取消和退款接口。
            return mock(ConfirmedTicketOperationExecutor.class);
        }

        /**
         * 创建可控制订单状态和退款金额的预览执行器替身。
         *
         * @return Mockito 订单操作预览执行器
         */
        @Bean
        TicketOperationPreviewExecutor ticketOperationPreviewExecutor() {
            // 每个测试显式声明业务预览结果，避免连接真实 MCP 服务。
            return mock(TicketOperationPreviewExecutor.class);
        }

        /**
         * 创建不会访问真实余票服务的购票确认前核验替身。
         *
         * @return 优先注入测试上下文的核验服务
         */
        @Bean
        @Primary
        PurchaseDraftRevalidationService testPurchaseDraftRevalidationService() {
            // 具体余票核验规则由独立单元测试覆盖，持久化测试只验证调用时机和状态机。
            return mock(PurchaseDraftRevalidationService.class);
        }
    }

    /**
     * @param userId 测试用户标识
     * @param turnId 测试轮次标识
     * @param context 已绑定主题的请求上下文
     */
    private record Fixture(String userId, String turnId, AgentRequestContext context) {
    }
}
