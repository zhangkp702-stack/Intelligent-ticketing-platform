package org.opengoofy.index12306.ai.agentservice.mcp.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.mcp.audit.ToolCallAuditService.ToolCallAuditEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证工具调用审计表和持久化服务能够保存非敏感诊断元数据。
 */
@ActiveProfiles("test")
@SpringBootTest
class ToolCallAuditPersistenceTests {

    @Autowired
    private ToolCallAuditService auditService;

    @Autowired
    private ToolCallRepository repository;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * 验证工具名称、关联上下文、指纹、计数和耗时可以持久化。
     */
    @Test
    void persistsToolCallWithoutRequestOrResponseBody() {
        ToolCallAuditEvent event = new ToolCallAuditEvent(
                "request-tool-a",
                "conversation-a",
                "topic-a",
                "turn-a",
                "query_tickets",
                "index12306-ticket-mcp-server",
                ToolCallOutcome.SUCCESS,
                38,
                null,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                3,
                null);

        // 审计服务在独立事务中分配调用序号并写入 V2 表。
        String id = auditService.record(event);
        ToolCallEntity persisted = repository.findById(id).orElseThrow();

        assertThat(persisted.getRequestId()).isEqualTo("request-tool-a");
        assertThat(persisted.getToolName()).isEqualTo("query_tickets");
        assertThat(persisted.getInvocationNo()).isEqualTo(1);
        assertThat(persisted.getResponseItemCount()).isEqualTo(3);
        assertThat(persisted.getRequestFingerprint()).hasSize(64);

        // 指标只按有限工具名和结果分类记录，不包含请求标识或工具参数。
        assertThat(meterRegistry.get("agent.tool.calls")
                .tags("tool", "query_tickets", "outcome", "SUCCESS", "category", "NONE")
                .counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("agent.tool.call.duration")
                .tags("tool", "query_tickets", "outcome", "SUCCESS")
                .timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(38);
    }
}
