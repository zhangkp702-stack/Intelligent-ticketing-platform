package org.opengoofy.index12306.ai.agentservice.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.action.mcp.PurchaseDraftTools;
import org.opengoofy.index12306.ai.agentservice.chat.model.IntentActionModels.PurchaseIntentData;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PurchaseWorkflowContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.ResolvedPassenger;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.PassengerResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.mcp.PurchasePassengerTools;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证确定性购票链路在选中车次后使用余票结果中的真实停靠站创建草案。
 */
class PurchaseChainServiceTests {

    /**
     * 用户输入城市简称时，乘车人工作流和购票草案必须使用车次返回的完整站名。
     */
    @Test
    @SuppressWarnings("unchecked")
    void usesSelectedTrainStationsForPassengerResolutionAndDraft() {
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        ToolCallback stationCallback = callback("resolve_station");
        when(stationCallback.call(any(String.class), any(ToolContext.class)))
                .thenReturn(
                        "[{\"name\":\"北京南\",\"code\":\"VNP\"}]",
                        "[{\"name\":\"上海虹桥\",\"code\":\"AOH\"}]");
        ToolCallback ticketCallback = callback("query_tickets");
        when(ticketCallback.call(any(String.class), any(ToolContext.class))).thenReturn("""
                {
                  "trains": [
                    {
                      "trainId": "train-1",
                      "trainNumber": "G9001",
                      "departure": "北京南",
                      "arrival": "上海虹桥",
                      "departureTime": "07:00",
                      "seats": [{"type": 2, "quantity": 10}]
                    }
                  ]
                }
                """);
        ToolCallback passengerCallback = callback("find_my_passengers_by_name");
        ToolCallbackProvider provider = ToolCallbackProvider.from(
                stationCallback, ticketCallback, passengerCallback);
        when(providers.orderedStream()).thenAnswer(ignored -> Stream.of(provider));

        PurchasePassengerTools passengerTools = mock(PurchasePassengerTools.class);
        when(passengerTools.resolvePurchasePassengers(
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PassengerResolutionResult(
                        PassengerResolutionStatus.RESOLVED,
                        "workflow-1",
                        List.of(new ResolvedPassenger("passenger-1", "万重山")),
                        List.of(),
                        "乘车人已匹配"));
        PurchaseDraftTools draftTools = mock(PurchaseDraftTools.class);
        PurchaseWorkflowService workflowService = mock(PurchaseWorkflowService.class);
        when(workflowService.findReadyDraftContext("user-1", "conversation-1"))
                .thenReturn(Optional.empty());
        PurchaseChainService service = new PurchaseChainService(
                providers,
                new McpToolContextFactory(),
                passengerTools,
                draftTools,
                workflowService,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC));

        // 模拟用户只提供城市简称，余票查询返回所选车次的真实始发和到达站。
        PurchaseChainService.PurchaseChainResult result = service.execute(
                new AgentRequestContext(
                        "request-1", "user-1", "tester", "conversation-1", "turn-1"),
                new PurchaseIntentData(
                        "北京", "上海", "2026-07-28", "G9001", "07:00",
                        "SECOND_CLASS", List.of("万重山")));

        assertThat(result.message()).contains("购票草案");
        verify(passengerTools).resolvePurchasePassengers(
                eq("train-1"), eq("北京南"), eq("上海虹桥"), eq("2026-07-28"),
                eq(List.of("万重山")), eq(PurchaseSeatClass.SECOND_CLASS), any(ToolContext.class));
        verify(draftTools).prepareTicketPurchase(
                eq("train-1"), eq("北京南"), eq("上海虹桥"), eq("2026-07-28"),
                eq(List.of(new PurchaseDraftTools.PassengerDraftInput(
                        "passenger-1", PurchaseSeatClass.SECOND_CLASS))),
                eq(List.of()), any(ToolContext.class));
    }

    /**
     * 前端已经完成乘车人选择时，固定链必须直接使用数据库工作流创建草案。
     */
    @Test
    @SuppressWarnings("unchecked")
    void createsDraftFromReadyWorkflowWithoutRepeatingIntentExtraction() {
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        PurchasePassengerTools passengerTools = mock(PurchasePassengerTools.class);
        PurchaseDraftTools draftTools = mock(PurchaseDraftTools.class);
        PurchaseWorkflowService workflowService = mock(PurchaseWorkflowService.class);
        PurchaseWorkflowContext readyContext = new PurchaseWorkflowContext(
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-28",
                List.of("万重山"),
                List.of(),
                List.of("passenger-1"),
                PurchaseSeatClass.FIRST_CLASS.code(),
                List.of("3A"));
        when(workflowService.findReadyDraftContext("user-1", "conversation-1"))
                .thenReturn(Optional.of(readyContext));
        PurchaseChainService service = new PurchaseChainService(
                providers,
                new McpToolContextFactory(),
                passengerTools,
                draftTools,
                workflowService,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC));

        // 当前轮即使没有重复携带完整行程字段，也必须消费已校验的数据库上下文。
        PurchaseChainService.PurchaseChainResult result = service.execute(
                new AgentRequestContext(
                        "request-1", "user-1", "tester", "conversation-1", "turn-1"),
                null);

        assertThat(result.message()).contains("购票草案");
        verify(draftTools).prepareTicketPurchase(
                eq("train-1"),
                eq("北京南"),
                eq("上海虹桥"),
                eq("2026-07-28"),
                eq(List.of(new PurchaseDraftTools.PassengerDraftInput(
                        "passenger-1", PurchaseSeatClass.FIRST_CLASS))),
                eq(List.of("3A")),
                any(ToolContext.class));
    }

    /**
     * 创建带稳定工具名称的 MCP 回调替身。
     *
     * @param name 工具名称
     * @return 可配置响应的工具回调
     */
    private ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
