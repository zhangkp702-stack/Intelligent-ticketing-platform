package org.opengoofy.index12306.ai.agentservice.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ActionStatusView;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ConfirmPurchaseCommand;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchasePassenger;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchasePayload;
import org.opengoofy.index12306.ai.agentservice.action.domain.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.TopicEntity;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
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
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private ConfirmedPurchaseExecutor executor;

    /**
     * 清理共享 Spring 测试上下文中的执行器调用记录。
     */
    @BeforeEach
    void resetExecutor() {
        // 每个测试独立断言真实购票执行次数，避免上下文缓存造成相互影响。
        reset(executor);
    }

    /**
     * 验证合法令牌只执行一次真实购票，重复确认直接复用持久化成功结果。
     */
    @Test
    void confirmedPurchaseExecutesOnceAndReusesStoredResult() {
        Fixture fixture = createRunningTurn();
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
        verify(executor, times(1)).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("alice"));
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
        TopicEntity topic = conversationMemoryService.createTopic(
                userId, conversation.getId(), unique("topic"), "购票确认");

        // 草案只能在当前运行轮次内创建，因此先启动轮次并绑定可信主题。
        ConversationMemoryService.StartedTurn turn = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), requestId, requestId, "购买测试车票", 5));
        conversationMemoryService.assignTurnToTopic(userId, turn.turnId(), topic.getId());
        AgentRequestContext context = new AgentRequestContext(
                requestId, userId, "alice", conversation.getId(), turn.turnId(), topic.getId());
        return new Fixture(userId, turn.turnId(), context);
    }

    /**
     * 创建稳定的购票草案参数。
     *
     * @return 包含一个乘车人的购票参数
     */
    private PurchasePayload payload() {
        return new PurchasePayload(
                "train-100", "北京南", "上海虹桥",
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
    }

    /**
     * @param userId 测试用户标识
     * @param turnId 测试轮次标识
     * @param context 已绑定主题的请求上下文
     */
    private record Fixture(String userId, String turnId, AgentRequestContext context) {
    }
}
