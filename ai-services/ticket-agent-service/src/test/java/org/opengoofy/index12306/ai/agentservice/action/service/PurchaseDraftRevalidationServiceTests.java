package org.opengoofy.index12306.ai.agentservice.action.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchasePassenger;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.PurchasePayload;
import org.opengoofy.index12306.ai.agentservice.chat.exception.AgentChatException;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证购票确认前会重新查询车次，并拒绝余票已经不足的旧草案。
 */
class PurchaseDraftRevalidationServiceTests {

    /**
     * 最新查询仍存在同一车次且余票满足人数时允许继续确认。
     */
    @Test
    @SuppressWarnings("unchecked")
    void acceptsUnchangedTrainWithEnoughSeats() {
        ToolCallback stationTool = tool("resolve_station");
        when(stationTool.call(any(String.class), any(ToolContext.class)))
                .thenReturn(
                        "[{\"name\":\"北京南\",\"code\":\"VNP\"}]",
                        "[{\"name\":\"上海虹桥\",\"code\":\"AOH\"}]");
        ToolCallback ticketTool = tool("query_tickets");
        when(ticketTool.call(any(String.class), any(ToolContext.class))).thenReturn("""
                {
                  "trains": [{
                    "trainId": "train-1",
                    "departure": "北京南",
                    "arrival": "上海虹桥",
                    "seats": [{"type": 1, "quantity": 2}]
                  }]
                }
                """);
        PurchaseDraftRevalidationService service = service(stationTool, ticketTool);

        // 两名乘车人的一等座余票仍有两张，重新核验不应阻止后续令牌消费。
        assertThatCode(() -> service.revalidate(payload(), context()))
                .doesNotThrowAnyException();
    }

    /**
     * 最新余票少于草案乘车人数时拒绝确认并要求重新生成草案。
     */
    @Test
    @SuppressWarnings("unchecked")
    void rejectsDraftWhenSeatsHaveChanged() {
        ToolCallback stationTool = tool("resolve_station");
        when(stationTool.call(any(String.class), any(ToolContext.class)))
                .thenReturn(
                        "[{\"name\":\"北京南\",\"code\":\"VNP\"}]",
                        "[{\"name\":\"上海虹桥\",\"code\":\"AOH\"}]");
        ToolCallback ticketTool = tool("query_tickets");
        when(ticketTool.call(any(String.class), any(ToolContext.class))).thenReturn("""
                {
                  "trains": [{
                    "trainId": "train-1",
                    "departure": "北京南",
                    "arrival": "上海虹桥",
                    "seats": [{"type": 1, "quantity": 1}]
                  }]
                }
                """);
        PurchaseDraftRevalidationService service = service(stationTool, ticketTool);

        // 草案需要两张一等座，最新只剩一张时必须保留确认机会但禁止执行旧草案。
        assertThatThrownBy(() -> service.revalidate(payload(), context()))
                .isInstanceOf(AgentChatException.class)
                .hasMessageContaining("余票状态已经变化");
    }

    /**
     * 创建使用给定固定只读工具的核验服务。
     *
     * @param callbacks 本轮可用工具
     * @return 不连接真实 MCP 服务的核验服务
     */
    @SuppressWarnings("unchecked")
    private PurchaseDraftRevalidationService service(ToolCallback... callbacks) {
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        ToolCallbackProvider provider = ToolCallbackProvider.from(callbacks);
        when(providers.orderedStream()).thenAnswer(ignored -> Stream.of(provider));
        return new PurchaseDraftRevalidationService(
                providers,
                new McpToolContextFactory(),
                new ObjectMapper());
    }

    /**
     * 创建需要两张一等座的稳定购票草案。
     *
     * @return 可用于重新核验的购票参数
     */
    private PurchasePayload payload() {
        return new PurchasePayload(
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-30",
                List.of(
                        new PurchasePassenger("passenger-1", 1),
                        new PurchasePassenger("passenger-2", 1)),
                List.of());
    }

    /**
     * 创建固定确认请求上下文。
     *
     * @return 服务端身份上下文
     */
    private AgentRequestContext context() {
        return new AgentRequestContext(
                "request-1", "user-1", "tester", "conversation-1", "turn-1");
    }

    /**
     * 创建带稳定名称的工具回调替身。
     *
     * @param name 工具名称
     * @return 可配置响应的工具回调
     */
    private ToolCallback tool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
