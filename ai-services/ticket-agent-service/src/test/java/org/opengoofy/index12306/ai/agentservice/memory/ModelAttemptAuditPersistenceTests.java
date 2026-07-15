package org.opengoofy.index12306.ai.agentservice.memory;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ModelCallEntity;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ModelCallRepository;
import org.opengoofy.index12306.ai.agentservice.model.config.ModelRole;
import org.opengoofy.index12306.ai.agentservice.model.observability.ModelAttemptContext;
import org.opengoofy.index12306.ai.agentservice.model.observability.ModelAttemptEvent;
import org.opengoofy.index12306.ai.agentservice.model.observability.ModelAttemptOutcome;
import org.opengoofy.index12306.ai.agentservice.model.observability.ModelAttemptRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证模型路由观测事件会写入持久化审计表并保留显式请求关联。
 */
@ActiveProfiles("test")
@SpringBootTest
class ModelAttemptAuditPersistenceTests {

    @Autowired
    private ModelAttemptRecorder attemptRecorder;

    @Autowired
    private ModelCallRepository modelCallRepository;

    /**
     * 验证成功模型尝试的角色、降级位置和会话关联字段均可持久化追踪。
     */
    @Test
    void persistsAttemptWithExplicitContext() {
        ModelAttemptContext context = new ModelAttemptContext(
                "request-audit", "conversation-audit", "topic-audit", "turn-audit");
        ModelAttemptEvent event = new ModelAttemptEvent(
                Instant.now(),
                ModelRole.TOPIC_ROUTE,
                "route-secondary",
                "bailian",
                "qwen-flash",
                ModelAttemptOutcome.SUCCESS,
                null,
                42,
                1,
                false,
                null,
                context);

        // 记录器同时更新内存观测数据和独立事务审计记录。
        String modelCallId = attemptRecorder.record(event);
        ModelCallEntity persisted = modelCallRepository.findById(modelCallId).orElseThrow();

        assertThat(persisted.getRequestId()).isEqualTo("request-audit");
        assertThat(persisted.getConversationId()).isEqualTo("conversation-audit");
        assertThat(persisted.getTopicId()).isEqualTo("topic-audit");
        assertThat(persisted.getTurnId()).isEqualTo("turn-audit");
        assertThat(persisted.getFallbackIndex()).isEqualTo(1);
        assertThat(persisted.getAttemptNo()).isEqualTo(2);
    }
}
