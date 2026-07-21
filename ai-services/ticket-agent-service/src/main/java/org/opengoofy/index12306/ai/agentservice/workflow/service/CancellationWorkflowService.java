package org.opengoofy.index12306.ai.agentservice.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.entity.AgentWorkflowEntity;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.CancellableOrderOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.CancellationWorkflowContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.OrderResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行取消订单工作流中的本人订单定位、选择提交和草案边界校验。
 */
@Service
public class CancellationWorkflowService {

    private final AgentWorkflowService workflowService;
    private final WorkflowInteractionTracker interactionTracker;
    private final ObjectMapper objectMapper;

    /**
     * 创建取消订单工作流阶段服务。
     *
     * @param workflowService 通用工作流生命周期服务
     * @param interactionTracker 本轮结构化交互跟踪器
     * @param objectMapper 工作流上下文 JSON 转换器
     */
    public CancellationWorkflowService(
            AgentWorkflowService workflowService,
            WorkflowInteractionTracker interactionTracker,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.interactionTracker = interactionTracker;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据用户提供的订单号、车次和日期定位可取消订单，无法唯一确定时生成订单选择表单。
     *
     * @param requestContext 当前请求上下文
     * @param requestedOrderSn 用户提供的订单号，可为空
     * @param requestedTrainNumber 用户提供的车次号，可为空
     * @param requestedRidingDate 用户提供的乘车日期，可为空
     * @param orders 当前账号的订单列表
     * @return 订单定位结果
     */
    public OrderResolutionResult resolveOrder(
            AgentRequestContext requestContext,
            String requestedOrderSn,
            String requestedTrainNumber,
            String requestedRidingDate,
            List<CancellableOrderOption> orders) {
        String orderSn = normalize(requestedOrderSn);
        String trainNumber = normalize(requestedTrainNumber);
        String ridingDate = normalize(requestedRidingDate);

        // 只有下游明确标记为可取消且具备订单号的本人订单才能进入候选集合。
        List<CancellableOrderOption> cancellableOrders = orders == null ? List.of() : orders.stream()
                .filter(order -> order != null
                        && StringUtils.hasText(order.orderSn())
                        && Boolean.TRUE.equals(order.canCancel()))
                .collect(Collectors.toMap(
                        CancellableOrderOption::orderSn,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        if (cancellableOrders.isEmpty()) {
            return new OrderResolutionResult(
                    OrderResolutionStatus.NO_CANCELLABLE_ORDERS,
                    null,
                    null,
                    "当前账号没有可以取消的订单");
        }

        // 订单号优先精确匹配；未提供订单号时才使用车次和日期收窄候选范围。
        List<CancellableOrderOption> matches = cancellableOrders.stream()
                .filter(order -> orderSn == null || orderSn.equals(order.orderSn()))
                .filter(order -> trainNumber == null || trainNumber.equalsIgnoreCase(order.trainNumber()))
                .filter(order -> ridingDate == null || ridingDate.equals(order.ridingDate()))
                .toList();
        boolean uniquelyResolved = matches.size() == 1;
        CancellationWorkflowContext workflowContext = new CancellationWorkflowContext(
                orderSn,
                trainNumber,
                ridingDate,
                cancellableOrders,
                uniquelyResolved ? matches.get(0).orderSn() : null);
        AgentWorkflowEntity workflow = createWorkflow(requestContext, workflowContext, uniquelyResolved);
        if (uniquelyResolved) {
            return new OrderResolutionResult(
                    OrderResolutionStatus.RESOLVED,
                    workflow.getId(),
                    matches.get(0),
                    "已唯一定位本人可取消订单，可以继续生成取消草案");
        }

        // 条件未命中时展示全部可取消订单，避免用户被困在错误的模型抽取结果中。
        List<CancellableOrderOption> selectionOrders = matches.isEmpty() ? cancellableOrders : matches;
        OrderSelectionView selection = new OrderSelectionView(
                workflow.getId(),
                workflow.getStage(),
                matches.isEmpty() && (orderSn != null || trainNumber != null || ridingDate != null)
                        ? "没有唯一匹配到你描述的订单，请从可取消订单中选择"
                        : "请选择需要取消的订单",
                selectionOrders);
        interactionTracker.markRequired(requestContext.turnId(), selection);
        return new OrderResolutionResult(
                OrderResolutionStatus.SELECTION_REQUIRED,
                workflow.getId(),
                null,
                "需要用户在本人可取消订单列表中完成选择");
    }

    /**
     * 提交用户在结构化表单中选择的订单并推进到草案创建阶段。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param request 订单选择请求
     * @return 已校验的订单和下一阶段
     */
    public OrderSelectionResult selectOrder(
            String userId,
            String workflowId,
            OrderSelectionRequest request) {
        AgentWorkflowEntity workflow = workflowService.findActiveById(userId, workflowId)
                .orElseThrow(() -> new IllegalArgumentException("取消订单工作流不存在或已经失效"));
        if (workflow.getWorkflowType() != WorkflowType.ORDER_CANCELLATION
                || workflow.getStage() != WorkflowStage.SELECTING_ORDER) {
            throw new IllegalStateException("当前工作流不处于订单选择阶段");
        }
        String orderSn = request == null ? null : normalize(request.orderSn());
        if (orderSn == null) {
            throw new IllegalArgumentException("请选择需要取消的订单");
        }
        CancellationWorkflowContext context = readContext(workflow.getContextJson());

        // 订单号必须来自服务端保存的本人可取消候选集合，浏览器不能提交任意订单号。
        CancellableOrderOption selected = context.orderOptions().stream()
                .filter(order -> orderSn.equals(order.orderSn()) && Boolean.TRUE.equals(order.canCancel()))
                .findFirst()
                .orElseThrow(() -> new SecurityException("选择中包含不属于当前候选集的订单"));
        CancellationWorkflowContext updatedContext = new CancellationWorkflowContext(
                context.requestedOrderSn(),
                context.requestedTrainNumber(),
                context.requestedRidingDate(),
                context.orderOptions(),
                selected.orderSn());
        AgentWorkflowEntity updated = workflowService.advance(
                userId,
                workflowId,
                WorkflowStage.SELECTING_ORDER,
                WorkflowStage.CREATING_DRAFT,
                writeContext(updatedContext));
        return new OrderSelectionResult(updated.getId(), updated.getStage(), selected);
    }

    /**
     * 恢复会话中等待用户选择的取消订单表单。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @return 可恢复的订单选择视图
     */
    public Optional<OrderSelectionView> findPendingSelection(String userId, String conversationId) {
        // 页面刷新只恢复仍处于 SELECTING_ORDER 的本人工作流。
        return workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.ORDER_CANCELLATION)
                .filter(workflow -> workflow.getStage() == WorkflowStage.SELECTING_ORDER)
                .map(workflow -> {
                    CancellationWorkflowContext context = readContext(workflow.getContextJson());
                    return new OrderSelectionView(
                            workflow.getId(), workflow.getStage(), "请选择需要取消的订单", context.orderOptions());
                });
    }

    /**
     * 生成供回答模型继续取消订单链路的服务端工作流提示。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @return 不含敏感字段的活动工作流提示
     */
    public Optional<String> activeWorkflowPrompt(String userId, String conversationId) {
        // 模型只能读取服务端已确认的订单号和阶段，不能自行替换选择结果。
        return workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.ORDER_CANCELLATION)
                .map(workflow -> "当前取消订单工作流由服务端维护，workflowId=" + workflow.getId()
                        + "，stage=" + workflow.getStage().name() + "，context=" + workflow.getContextJson()
                        + "。stage=CREATING_DRAFT 时必须使用 selectedOrderSn 创建取消草案；"
                        + "stage=SELECTING_ORDER 时必须等待用户提交订单选择表单。");
    }

    /**
     * 校验模型提交的取消草案是否使用服务端已经选定的订单号。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @param orderSn 草案中的订单号
     */
    public void validateDraft(String userId, String conversationId, String orderSn) {
        AgentWorkflowEntity workflow = workflowService.findActive(userId, conversationId)
                .filter(candidate -> candidate.getWorkflowType() == WorkflowType.ORDER_CANCELLATION)
                .orElseThrow(() -> new IllegalStateException("取消草案缺少有效的服务端工作流"));
        if (workflow.getStage() != WorkflowStage.CREATING_DRAFT) {
            throw new IllegalStateException("当前取消订单工作流尚未进入草案创建阶段");
        }
        CancellationWorkflowContext context = readContext(workflow.getContextJson());

        // 草案订单号必须与唯一匹配或用户勾选结果完全一致。
        if (!StringUtils.hasText(context.selectedOrderSn())
                || !context.selectedOrderSn().equals(normalize(orderSn))) {
            throw new SecurityException("取消草案订单与服务端已确认结果不一致");
        }
    }

    /**
     * 取消草案持久化成功后完成当前会话的取消订单工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     */
    public void completeAfterDraft(String userId, String conversationId) {
        // 只有草案创建成功后才结束工作流，失败时保留当前阶段供用户重试。
        workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.ORDER_CANCELLATION)
                .ifPresent(workflow -> workflowService.complete(
                        userId, workflow.getId(), workflow.getStage(), workflow.getContextJson()));
    }

    /**
     * 创建订单选择阶段工作流，并在唯一匹配时推进到草案阶段。
     */
    private AgentWorkflowEntity createWorkflow(
            AgentRequestContext requestContext,
            CancellationWorkflowContext context,
            boolean resolved) {
        String contextJson = writeContext(context);
        AgentWorkflowEntity workflow = workflowService.startOrResume(
                requestContext.userId(),
                requestContext.conversationId(),
                WorkflowType.ORDER_CANCELLATION,
                WorkflowStage.SELECTING_ORDER,
                contextJson);
        if (workflow.getStage() != WorkflowStage.SELECTING_ORDER) {
            throw new IllegalStateException("当前取消订单工作流已经完成订单选择");
        }
        if (!resolved) {
            return workflowService.updateContext(
                    requestContext.userId(), workflow.getId(), WorkflowStage.SELECTING_ORDER, contextJson);
        }
        return workflowService.advance(
                requestContext.userId(), workflow.getId(), WorkflowStage.SELECTING_ORDER,
                WorkflowStage.CREATING_DRAFT, contextJson);
    }

    /**
     * 序列化取消订单工作流上下文。
     */
    private String writeContext(CancellationWorkflowContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存取消订单工作流上下文", exception);
        }
    }

    /**
     * 反序列化取消订单工作流上下文。
     */
    private CancellationWorkflowContext readContext(String contextJson) {
        try {
            return objectMapper.readValue(contextJson, CancellationWorkflowContext.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("取消订单工作流上下文已损坏", exception);
        }
    }

    /**
     * 规范化允许为空的模型抽取文本。
     */
    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
