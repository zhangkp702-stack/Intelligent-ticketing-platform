package org.opengoofy.index12306.ai.agentservice.workflow.service;

import org.opengoofy.index12306.ai.agentservice.action.mcp.TicketOperationDraftTools;
import org.opengoofy.index12306.ai.agentservice.chat.model.IntentActionModels.CancellationIntentData;
import org.opengoofy.index12306.ai.agentservice.chat.model.IntentActionModels.RefundIntentData;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.mcp.context.McpToolContextFactory;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundWorkflowContext;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.OrderResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.RefundResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.mcp.CancellationOrderTools;
import org.opengoofy.index12306.ai.agentservice.workflow.mcp.RefundTicketTools;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 在意图识别完成后固定执行取消订单和退票的查询、解析与草案创建顺序。
 */
@Service
public class TicketOperationChainService {

    private final CancellationOrderTools cancellationOrderTools;
    private final RefundTicketTools refundTicketTools;
    private final TicketOperationDraftTools draftTools;
    private final CancellationWorkflowService cancellationWorkflowService;
    private final RefundWorkflowService refundWorkflowService;
    private final McpToolContextFactory toolContextFactory;

    /**
     * 创建交易操作代码链。
     *
     * @param cancellationOrderTools 取消订单查询和定位工具
     * @param refundTicketTools 退票订单、车票查询和定位工具
     * @param draftTools 取消和退票草案创建工具
     * @param cancellationWorkflowService 取消订单工作流状态服务
     * @param refundWorkflowService 退票工作流状态服务
     * @param toolContextFactory 服务端工具上下文工厂
     */
    public TicketOperationChainService(
            CancellationOrderTools cancellationOrderTools,
            RefundTicketTools refundTicketTools,
            TicketOperationDraftTools draftTools,
            CancellationWorkflowService cancellationWorkflowService,
            RefundWorkflowService refundWorkflowService,
            McpToolContextFactory toolContextFactory) {
        this.cancellationOrderTools = cancellationOrderTools;
        this.refundTicketTools = refundTicketTools;
        this.draftTools = draftTools;
        this.cancellationWorkflowService = cancellationWorkflowService;
        this.refundWorkflowService = refundWorkflowService;
        this.toolContextFactory = toolContextFactory;
    }

    /**
     * 固定执行本人订单查询、可取消目标定位和取消草案创建。
     *
     * @param context 当前请求上下文
     * @param request 意图模型提取的订单定位字段
     * @return 供前端展示的确定性链路结果
     */
    public OperationChainResult executeCancellation(
            AgentRequestContext context,
            CancellationIntentData request) {
        ToolContext toolContext = toolContext(context);

        // 前端已经完成订单选择时，直接使用服务端持久化的订单号创建草案。
        String readyOrderSn = cancellationWorkflowService
                .findReadyDraftOrderSn(context.userId(), context.conversationId())
                .orElse(null);
        if (StringUtils.hasText(readyOrderSn)) {
            draftTools.prepareOrderCancellation(readyOrderSn, toolContext);
            return new OperationChainResult("取消订单草案已生成，请核对后确认取消。");
        }

        // 没有就绪选择时查询本人订单，并按分类模型提取的条件定位唯一可取消目标。
        OrderResolutionResult resolution = cancellationOrderTools.resolveOrderCancellation(
                request == null ? null : request.orderSn(),
                request == null ? null : request.trainNumber(),
                request == null ? null : request.ridingDate(),
                toolContext);
        if (resolution.status() != OrderResolutionStatus.RESOLVED) {
            return new OperationChainResult(resolution.message());
        }

        // 唯一订单由服务端确认后创建草案，真实取消仍由独立确认接口执行。
        draftTools.prepareOrderCancellation(resolution.selectedOrder().orderSn(), toolContext);
        return new OperationChainResult("取消订单草案已生成，请核对后确认取消。");
    }

    /**
     * 固定执行本人可退订单查询、退票预览、范围解析和退票草案创建。
     *
     * @param context 当前请求上下文
     * @param request 意图模型提取的订单和乘车人字段
     * @return 供前端展示的确定性链路结果
     */
    public OperationChainResult executeRefund(
            AgentRequestContext context,
            RefundIntentData request) {
        ToolContext toolContext = toolContext(context);

        // 前端已经完成订单或车票选择时，直接消费服务端保存的退票范围。
        RefundWorkflowContext readyContext = refundWorkflowService
                .findReadyDraftContext(context.userId(), context.conversationId())
                .orElse(null);
        if (readyContext != null) {
            prepareRefundDraft(readyContext, toolContext);
            return new OperationChainResult("退票草案已生成，请核对后确认退票。");
        }

        // 固定查询本人订单、详情和实时退票预览，再由服务端按姓名解析车票范围。
        List<String> passengerNames = request == null || request.passengerNames() == null
                ? List.of() : request.passengerNames();
        RefundResolutionResult resolution = refundTicketTools.resolveTicketRefund(
                request == null ? null : request.orderSn(),
                request == null ? null : request.trainNumber(),
                request == null ? null : request.ridingDate(),
                passengerNames,
                toolContext);
        if (resolution.status() != RefundResolutionStatus.RESOLVED) {
            return new OperationChainResult(resolution.message());
        }

        // 服务端确认订单和退票范围后创建草案，回答模型不再参与工具选择。
        List<String> selectedIds = resolution.selectedTickets().stream()
                .map(ticket -> ticket.orderItemId())
                .toList();
        List<String> draftItemIds = Integer.valueOf(1).equals(resolution.refundType())
                ? List.of() : selectedIds;
        draftTools.prepareTicketRefund(
                resolution.selectedOrder().orderSn(),
                resolution.refundType(),
                draftItemIds,
                toolContext);
        return new OperationChainResult("退票草案已生成，请核对后确认退票。");
    }

    /**
     * 使用已经持久化的退票范围创建草案。
     *
     * @param context 服务端退票工作流上下文
     * @param toolContext 当前工具上下文
     */
    private void prepareRefundDraft(
            RefundWorkflowContext context,
            ToolContext toolContext) {
        // 全部退票使用空列表表达整单，部分退票传递服务端已经确认的子订单集合。
        List<String> orderItemIds = Integer.valueOf(1).equals(context.refundType())
                ? List.of() : context.selectedOrderItemIds();
        draftTools.prepareTicketRefund(
                context.selectedOrderSn(),
                context.refundType(),
                orderItemIds,
                toolContext);
    }

    /**
     * 将当前请求身份转换为本地工具不可篡改的上下文。
     *
     * @param context 当前请求上下文
     * @return 供本地工具和 MCP 回调使用的上下文
     */
    private ToolContext toolContext(AgentRequestContext context) {
        // 用户、会话和轮次身份全部来自服务端请求，不接收模型参数。
        return new ToolContext(toolContextFactory.create(context));
    }

    /**
     * @param message 固定业务链路生成的用户提示
     */
    public record OperationChainResult(String message) {
    }
}
