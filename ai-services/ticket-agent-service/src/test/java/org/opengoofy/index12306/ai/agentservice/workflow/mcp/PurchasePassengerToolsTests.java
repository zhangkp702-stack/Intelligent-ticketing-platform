package org.opengoofy.index12306.ai.agentservice.workflow.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.action.enums.PurchaseSeatClass;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.ResolvedPassenger;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.PassengerResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.service.PurchaseWorkflowService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证购票乘车人包装工具能够保留身份并把 MCP 结果交给固定购票工作流。
 */
class PurchasePassengerToolsTests {

    /**
     * 验证真实 MCP 数组中的业务标识和姓名能够进入购票工作流。
     */
    @Test
    @SuppressWarnings("unchecked")
    void parsesPassengerArrayForPurchaseWorkflow() {
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        ToolCallback passengerCallback = callback("""
                [
                  {
                    "passengerId":"passenger-1",
                    "realName":"万重山",
                    "maskedIdCard":"1101********1955",
                    "discountType":0,
                    "verifyStatus":0
                  }
                ]
                """);
        when(providers.orderedStream()).thenReturn(Stream.of(ToolCallbackProvider.from(passengerCallback)));
        PurchaseWorkflowService workflowService = mock(PurchaseWorkflowService.class);
        PassengerResolutionResult expected = new PassengerResolutionResult(
                PassengerResolutionStatus.RESOLVED,
                "workflow-1",
                List.of(new ResolvedPassenger("passenger-1", "万重山")),
                List.of(),
                "乘车人已匹配");
        when(workflowService.resolvePassengers(
                any(), eq("train-1"), eq("北京南"), eq("上海虹桥"), eq("2026-07-28"),
                eq(List.of("万重山")), eq(PurchaseSeatClass.FIRST_CLASS), any()))
                .thenReturn(expected);
        PurchasePassengerTools tools = new PurchasePassengerTools(
                providers,
                new McpToolContextFactory(),
                workflowService,
                new ObjectMapper());

        // 本地包装工具使用服务端上下文调用远端 MCP，并把解析后的候选项交给工作流。
        PassengerResolutionResult actual = tools.resolvePurchasePassengers(
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-28",
                List.of("万重山"),
                PurchaseSeatClass.FIRST_CLASS,
                toolContext());

        assertThat(actual).isEqualTo(expected);
        ArgumentCaptor<List<PassengerOption>> optionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(workflowService).resolvePassengers(
                any(), eq("train-1"), eq("北京南"), eq("上海虹桥"), eq("2026-07-28"),
                eq(List.of("万重山")), eq(PurchaseSeatClass.FIRST_CLASS), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue())
                .extracting(PassengerOption::passengerId, PassengerOption::realName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("passenger-1", "万重山"));
        verify(passengerCallback).call(
                eq("{\"realName\":\"万重山\"}"),
                any(ToolContext.class));
    }

    /**
     * 验证非空 MCP 数组缺少乘车人业务标识时按协议错误处理，不能误报账号没有乘车人。
     */
    @Test
    @SuppressWarnings("unchecked")
    void rejectsIncompletePassengerInsteadOfReportingEmptyAccount() {
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        ToolCallback passengerCallback = callback("""
                [{"passengerId":null,"realName":"万重山","maskedIdCard":"1101********1955"}]
                """);
        when(providers.orderedStream()).thenReturn(Stream.of(ToolCallbackProvider.from(passengerCallback)));
        PurchaseWorkflowService workflowService = mock(PurchaseWorkflowService.class);
        PurchasePassengerTools tools = new PurchasePassengerTools(
                providers,
                new McpToolContextFactory(),
                workflowService,
                new ObjectMapper());

        // 协议字段损坏必须终止本轮，不能把无效对象过滤后转换成 NO_PASSENGERS。
        assertThatThrownBy(() -> tools.resolvePurchasePassengers(
                "train-1",
                "北京南",
                "上海虹桥",
                "2026-07-28",
                List.of("万重山"),
                PurchaseSeatClass.FIRST_CLASS,
                toolContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少业务标识或姓名");
    }

    /**
     * 创建返回指定 JSON 的乘车人 MCP 回调。
     *
     * @param result MCP 响应正文
     * @return 具有稳定工具名称的回调替身
     */
    private ToolCallback callback(String result) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("find_my_passengers_by_name");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(any(String.class), any(ToolContext.class))).thenReturn(result);
        return callback;
    }

    /**
     * 创建包含完整用户和轮次身份的工具上下文。
     *
     * @return 模型不能修改的服务端工具上下文
     */
    private ToolContext toolContext() {
        AgentRequestContext context = new AgentRequestContext(
                "request-1", "user-1", "tester", "conversation-1", "turn-1");
        return new ToolContext(new McpToolContextFactory().create(context));
    }
}
