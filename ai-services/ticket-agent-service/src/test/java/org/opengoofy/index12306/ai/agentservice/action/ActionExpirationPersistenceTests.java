package org.opengoofy.index12306.ai.agentservice.action;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.opengoofy.index12306.ai.agentservice.action.security.ConfirmationTokenService;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionStateService;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证确认截止时间恰好跨越数据库领取边界时，过期状态不会随冲突异常回滚。
 */
@ActiveProfiles("test")
@SpringBootTest
@Import(ActionExpirationPersistenceTests.ClockConfiguration.class)
class ActionExpirationPersistenceTests {

    @Autowired
    private ActionStateService stateStore;

    @Autowired
    private ConfirmationTokenService tokenService;

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * 验证领取执行权时发现过期会提交 EXPIRED 状态且只记录一次过期指标。
     */
    @Test
    void claimPersistsExpirationBeforeReturningConflict() {
        String userId = unique("user");
        ConversationEntity conversation = conversationMemoryService.createConversation(userId, "确认过期测试");
        // 先由服务端创建可信轮次，再使用签发的提交令牌启动该轮次。
        ConversationMemoryService.PreparedTurn preparedTurn = conversationMemoryService.prepareTurn(
                userId, conversation.getId());
        ConversationMemoryService.StartedTurn turn = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), preparedTurn.turnId(),
                        preparedTurn.submissionToken(), "alice", "取消测试订单", 5));
        AgentRequestContext context = new AgentRequestContext(
                turn.turnId(), userId, "alice", conversation.getId(), turn.turnId());
        ActionDraftEntity action = stateStore.createDraft(
                context,
                AgentActionType.TICKET_CANCEL,
                "{\"orderSn\":\"order-1\"}",
                "a".repeat(64),
                clock.instant().plusSeconds(60));
        String confirmationToken = tokenService.issue(action);
        double expirationsBefore = expirationCount();

        // 模拟重新预览结束后刚好跨过确认截止时间，领取事务必须保留过期终态。
        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> stateStore.claim(
                userId,
                action.getId(),
                confirmationToken,
                unique("confirm"),
                unique("idempotency")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(stateStore.get(userId, action.getId()).getStatus())
                .isEqualTo(AgentActionStatus.EXPIRED);
        assertThat(expirationCount()).isEqualTo(expirationsBefore + 1);
        assertThat(stateStore.get(userId, action.getId()).getStatus())
                .isEqualTo(AgentActionStatus.EXPIRED);
        assertThat(expirationCount()).isEqualTo(expirationsBefore + 1);
    }

    /**
     * 读取取消操作草案的过期计数。
     *
     * @return 指标尚未创建时返回 0，否则返回当前累计值
     */
    private double expirationCount() {
        Counter counter = meterRegistry.find("agent.action.confirmation.expirations")
                .tag("actionType", "TICKET_CANCEL")
                .counter();
        return counter == null ? 0 : counter.count();
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
     * 为过期边界测试提供可推进且不依赖等待的统一时钟。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        /**
         * 创建优先于生产系统时钟的测试时钟。
         *
         * @return 初始时间固定的可变时钟
         */
        @Bean
        @Primary
        MutableClock actionExpirationClock() {
            return new MutableClock(Instant.parse("2026-07-29T08:00:00Z"));
        }
    }

    /**
     * 只在当前测试上下文中使用的可推进 UTC 时钟。
     */
    static final class MutableClock extends Clock {

        private Instant instant;

        /**
         * 创建指定初始时间的测试时钟。
         *
         * @param instant 初始时间
         */
        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 推进测试时间。
         *
         * @param duration 推进时长
         */
        private void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        /**
         * 返回 UTC 时区。
         *
         * @return UTC 时区
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * 当前测试始终使用 UTC，不需要创建新的时钟实例。
         *
         * @param zone 请求时区
         * @return 当前测试时钟
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * 返回当前可变测试时间。
         *
         * @return 当前时间
         */
        @Override
        public Instant instant() {
            return instant;
        }
    }
}
