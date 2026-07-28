package org.opengoofy.index12306.ai.agentservice.mcp.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 MCP 审计回调在保留审计行为的同时返回真实业务结果。
 */
class AuditedToolCallbackTests {

    /**
     * 验证单个 MCP 文本内容块会被解包为内部业务 JSON。
     */
    @Test
    void unwrapsSingleMcpTextContentBlock() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("resolve_station");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(any(String.class), any(ToolContext.class))).thenReturn("""
                [{"type":"text","text":"[{\\"name\\":\\"北京南\\",\\"code\\":\\"VNP\\"}]"}]
                """);
        ToolCallAuditService auditService = mock(ToolCallAuditService.class);
        AuditedToolCallback callback = new AuditedToolCallback(delegate, auditService, new ObjectMapper());

        // 业务调用方应直接收到站点数组，不能把外层 TextContent 对象误当成站点。
        String result = callback.call("{}", new ToolContext(Map.of()));

        assertThat(result).isEqualTo("[{\"name\":\"北京南\",\"code\":\"VNP\"}]");
    }
}
