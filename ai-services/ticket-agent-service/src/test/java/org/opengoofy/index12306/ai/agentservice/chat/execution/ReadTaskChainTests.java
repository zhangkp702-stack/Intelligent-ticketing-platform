package org.opengoofy.index12306.ai.agentservice.chat.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskSlots;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.WorkflowRelation;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证只读意图直接进入固定 MCP 调用链。
 */
class ReadTaskChainTests {

    /**
     * 验证查票固定执行两次站点解析和一次余票查询。
     */
    @Test
    @SuppressWarnings("unchecked")
    void trainQueryUsesDeterministicStationAndTicketChain() {
        ToolCallback stationTool = tool("resolve_station");
        ToolCallback ticketTool = tool("query_tickets");
        when(stationTool.call(anyString(), any(ToolContext.class))).thenAnswer(invocation -> {
            String arguments = invocation.getArgument(0);
            return arguments.contains("北京")
                    ? "[{\"name\":\"北京\",\"code\":\"BJP\",\"spell\":\"beijing\"}]"
                    : "[{\"name\":\"南京\",\"code\":\"NJH\",\"spell\":\"nanjing\"}]";
        });
        when(ticketTool.call(anyString(), any(ToolContext.class)))
                .thenReturn("{\"trains\":[{\"trainId\":\"train-1\",\"trainNumber\":\"G1\"}],\"truncated\":false}");
        ToolCallbackProvider provider = ToolCallbackProvider.from(stationTool, ticketTool);
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(ignored -> Stream.of(provider));
        ReadTaskChain service = new ReadTaskChain(
                providers, new McpToolContextFactory(), new ObjectMapper());

        StepVerifier.create(service.execute(
                        context(),
                        task(
                                AgentIntent.TRAIN_QUERY,
                                new TaskSlots(
                                        "北京", "南京", "2026-07-30", null,
                                        null, null, null, List.of(), null, null)),
                        List.of()))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(TaskExecutionStatus.SUCCESS);
                    assertThat(result.content()).contains("\"trainNumber\":\"G1\"");
                })
                .verifyComplete();

        // 固定链只能按既定顺序和次数调用，不把工具选择权交给模型。
        verify(stationTool, times(2)).call(anyString(), any(ToolContext.class));
        ArgumentCaptor<String> ticketArguments = ArgumentCaptor.forClass(String.class);
        verify(ticketTool).call(ticketArguments.capture(), any(ToolContext.class));
        assertThat(ticketArguments.getValue())
                .contains("\"fromStationCode\":\"BJP\"")
                .contains("\"toStationCode\":\"NJH\"")
                .contains("\"departureDate\":\"2026-07-30\"");
    }

    /**
     * 验证支付状态缺少订单号时不会调用外部工具。
     */
    @Test
    @SuppressWarnings("unchecked")
    void paymentQueryWithoutOrderNumberNeedsInput() {
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(ignored -> Stream.empty());
        ReadTaskChain service = new ReadTaskChain(
                providers, new McpToolContextFactory(), new ObjectMapper());

        StepVerifier.create(service.execute(
                        context(),
                        task(AgentIntent.PAYMENT_QUERY, emptySlots()),
                        List.of()))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(TaskExecutionStatus.NEEDS_INPUT);
                    assertThat(result.missingFields()).containsExactly("orderSn");
                })
                .verifyComplete();
    }

    /**
     * 创建带稳定名称的工具回调。
     *
     * @param name 工具名称
     * @return 固定链测试工具
     */
    private ToolCallback tool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        // 固定链通过定义名称选择唯一工具实现。
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    /**
     * 创建固定链测试请求上下文。
     *
     * @return 服务端身份上下文
     */
    private AgentRequestContext context() {
        return new AgentRequestContext(
                "request-1", "user-1", "tester", "conversation-1", "turn-1");
    }

    /**
     * 创建只读固定链测试任务。
     *
     * @param intent 当前查询意图
     * @param slots 已校验业务槽位
     * @return 单个查询任务
     */
    private PlannedTask task(
            AgentIntent intent,
            TaskSlots slots) {
        // 具体缺失字段由生产规划校验器计算，本测试按场景提供稳定值。
        List<String> missingFields = intent == AgentIntent.PAYMENT_QUERY
                && slots.orderSn() == null ? List.of("orderSn") : List.of();
        return new PlannedTask(
                "task-1",
                1,
                intent,
                "测试问题",
                "测试问题",
                slots,
                missingFields,
                List.of(),
                WorkflowRelation.INDEPENDENT,
                List.of());
    }

    /**
     * 创建空业务槽位。
     *
     * @return 所有字段为空的槽位
     */
    private TaskSlots emptySlots() {
        return new TaskSlots(
                null, null, null, null, null, null, null, List.of(), null, null);
    }
}
