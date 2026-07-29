package org.opengoofy.index12306.ai.agentservice.workflow.execution;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.mcp.TicketOperationDraftTools;
import org.opengoofy.index12306.ai.agentservice.chat.model.IntentActionModels.CancellationIntentData;
import org.opengoofy.index12306.ai.agentservice.chat.model.IntentActionModels.RefundIntentData;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.CancellableOrderOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundableOrderOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundableTicketOption;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.OrderResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.RefundResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.mcp.CancellationOrderTools;
import org.opengoofy.index12306.ai.agentservice.workflow.mcp.RefundTicketTools;
import org.opengoofy.index12306.ai.agentservice.workflow.service.CancellationWorkflowService;
import org.opengoofy.index12306.ai.agentservice.workflow.service.RefundWorkflowService;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证取消订单和退票在意图识别后由代码固定执行解析与草案创建顺序。
 */
class TicketOperationChainExecutorTests {

    /**
     * 验证取消订单唯一匹配后直接使用服务端订单号创建草案。
     */
    @Test
    void cancellationResolvesOrderBeforeCreatingDraft() {
        CancellationOrderTools cancellationTools = mock(CancellationOrderTools.class);
        RefundTicketTools refundTools = mock(RefundTicketTools.class);
        TicketOperationDraftTools draftTools = mock(TicketOperationDraftTools.class);
        CancellationWorkflowService cancellationWorkflow = mock(CancellationWorkflowService.class);
        RefundWorkflowService refundWorkflow = mock(RefundWorkflowService.class);
        TicketOperationChainExecutor service = service(
                cancellationTools, refundTools, draftTools, cancellationWorkflow, refundWorkflow);
        CancellableOrderOption selectedOrder = new CancellableOrderOption(
                "ORDER-1", "G9001", "北京南", "上海虹桥", "2026-07-28",
                "07:00", "11:35", "万重山", 55300, 0, true);
        when(cancellationWorkflow.findReadyDraftOrderSn("user-1", "conversation-1"))
                .thenReturn(Optional.empty());
        when(cancellationTools.resolveOrderCancellation(
                eq(null), eq("G9001"), eq("2026-07-28"), any(ToolContext.class)))
                .thenReturn(new OrderResolutionResult(
                        OrderResolutionStatus.RESOLVED, "workflow-1", selectedOrder, "已定位订单"));

        // 代码链只消费分类模型提取的定位字段，订单号最终以服务端解析结果为准。
        TicketOperationChainExecutor.OperationChainResult result = service.executeCancellation(
                context(), new CancellationIntentData(null, "G9001", "2026-07-28"));

        verify(cancellationTools).resolveOrderCancellation(
                eq(null), eq("G9001"), eq("2026-07-28"), any(ToolContext.class));
        verify(draftTools).prepareOrderCancellation(eq("ORDER-1"), any(ToolContext.class));
        assertThat(result.message()).contains("取消订单草案已生成");
    }

    /**
     * 验证退票唯一匹配后使用服务端车票标识创建部分退票草案。
     */
    @Test
    void refundResolvesTicketsBeforeCreatingDraft() {
        CancellationOrderTools cancellationTools = mock(CancellationOrderTools.class);
        RefundTicketTools refundTools = mock(RefundTicketTools.class);
        TicketOperationDraftTools draftTools = mock(TicketOperationDraftTools.class);
        CancellationWorkflowService cancellationWorkflow = mock(CancellationWorkflowService.class);
        RefundWorkflowService refundWorkflow = mock(RefundWorkflowService.class);
        TicketOperationChainExecutor service = service(
                cancellationTools, refundTools, draftTools, cancellationWorkflow, refundWorkflow);
        RefundableOrderOption selectedOrder = new RefundableOrderOption(
                "ORDER-2", "G9001", "北京南", "上海虹桥", "2026-07-28",
                "07:00", "11:35", "万重山", 55300, 30, true);
        RefundableTicketOption selectedTicket = new RefundableTicketOption(
                "ITEM-1", "万重山", 1, "08", "01A", 30, 55300);
        when(refundWorkflow.findReadyDraftContext("user-1", "conversation-1"))
                .thenReturn(Optional.empty());
        when(refundTools.resolveTicketRefund(
                eq(null), eq("G9001"), eq("2026-07-28"), eq(List.of("万重山")), any(ToolContext.class)))
                .thenReturn(new RefundResolutionResult(
                        RefundResolutionStatus.RESOLVED,
                        "workflow-2",
                        selectedOrder,
                        List.of(selectedTicket),
                        0,
                        "已定位车票"));

        // 乘车人姓名用于服务端解析，草案只能接收解析器返回的真实子订单标识。
        TicketOperationChainExecutor.OperationChainResult result = service.executeRefund(
                context(), new RefundIntentData(null, "G9001", "2026-07-28", List.of("万重山")));

        verify(refundTools).resolveTicketRefund(
                eq(null), eq("G9001"), eq("2026-07-28"), eq(List.of("万重山")), any(ToolContext.class));
        verify(draftTools).prepareTicketRefund(
                eq("ORDER-2"), eq(0), eq(List.of("ITEM-1")), any(ToolContext.class));
        assertThat(result.message()).contains("退票草案已生成");
    }

    /**
     * 创建不连接真实 MCP 和数据库的交易操作代码链。
     *
     * @param cancellationTools 取消订单解析工具替身
     * @param refundTools 退票解析工具替身
     * @param draftTools 草案创建工具替身
     * @param cancellationWorkflow 取消工作流替身
     * @param refundWorkflow 退票工作流替身
     * @return 待验证的固定交易代码链
     */
    private TicketOperationChainExecutor service(
            CancellationOrderTools cancellationTools,
            RefundTicketTools refundTools,
            TicketOperationDraftTools draftTools,
            CancellationWorkflowService cancellationWorkflow,
            RefundWorkflowService refundWorkflow) {
        // 工具上下文仅使用固定请求身份，不需要模型或网络依赖。
        return new TicketOperationChainExecutor(
                cancellationTools,
                refundTools,
                draftTools,
                cancellationWorkflow,
                refundWorkflow,
                new McpToolContextFactory());
    }

    /**
     * 创建固定的服务端请求上下文。
     *
     * @return 当前测试使用的身份和会话信息
     */
    private AgentRequestContext context() {
        // 固定标识便于验证代码链读取同一用户和会话的工作流状态。
        return new AgentRequestContext(
                "request-1", "user-1", "tester", "conversation-1", "turn-1");
    }
}
