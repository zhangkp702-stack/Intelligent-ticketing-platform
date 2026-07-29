package org.opengoofy.index12306.ai.agentservice.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.mcp.audit.service.ToolCallAuditService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证 MCP 工具延迟初始化后仍能进入 Agent 工具清单。
 */
class TicketMcpToolProviderConfigurationTests {

    /**
     * 验证审计提供器不会永久缓存 Bean 创建阶段的空工具数组。
     */
    @Test
    void discoversToolsAddedAfterProviderCreation() {
        AtomicReference<ToolCallbackProvider> delegate = new AtomicReference<>(
                () -> new ToolCallback[0]);
        ToolCallAuditService auditService = mock(ToolCallAuditService.class);
        ToolCallbackProvider provider = TicketMcpToolProviderConfiguration.auditedProvider(
                delegate::get, auditService, new ObjectMapper());

        // 模拟 Agent Bean 已创建但 MCP 客户端尚未完成工具发现。
        assertThat(provider.getToolCallbacks()).isEmpty();

        // 模拟 MCP 初始化完成，后续请求必须读取到新发布的工具。
        delegate.set(() -> new ToolCallback[]{mock(ToolCallback.class)});
        assertThat(provider.getToolCallbacks()).hasSize(1);
    }
}
