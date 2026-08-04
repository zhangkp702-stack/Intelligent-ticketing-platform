package org.opengoofy.index12306.ai.agentservice.action;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.mq.ActionReconciliationMessage;
import org.opengoofy.index12306.ai.agentservice.action.mq.ReconciliationMessageContractException;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证动作对账跨进程消息的版本兼容和最小安全约束。
 */
class ActionReconciliationMessageTests {

    /**
     * 验证滚动发布时遗留的旧消息会补齐固定业务域和事件类型。
     */
    @Test
    void shouldNormalizeLegacyMessageToStableEventIdentity() {
        // 模拟旧生产者序列化的四个业务字段，其余新增字段在反序列化后使用默认值。
        ActionReconciliationMessage message = new ActionReconciliationMessage(
                ActionReconciliationMessage.LEGACY_CONTRACT_VERSION,
                "event-1", null, null, "action-1", 1L, Instant.parse("2026-08-04T00:00:00Z"));

        // 兼容只补固定契约字段，最终仍由消费者与本地 Outbox 做二次验证。
        assertEquals(ActionReconciliationService.EVENT_NAMESPACE, message.eventNamespace());
        assertEquals(ActionReconciliationMessage.EVENT_TYPE, message.eventType());
    }

    /**
     * 验证未知版本不会被消费者当成可重试的业务消息处理。
     */
    @Test
    void shouldRejectUnsupportedContractVersion() {
        // 未识别的协议不能降级处理，避免字段语义变化后错误领取 Inbox。
        assertThrows(ReconciliationMessageContractException.class, () -> new ActionReconciliationMessage(
                2, "event-1", ActionReconciliationService.EVENT_NAMESPACE,
                ActionReconciliationMessage.EVENT_TYPE, "action-1", 1L, Instant.now()));
    }
}
