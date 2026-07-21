package org.opengoofy.index12306.ai.agentservice.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dao.entity.AgentWorkflowEntity;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundOrderSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundOrderSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundOrderSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundResolutionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundTicketSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundTicketSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundTicketSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundWorkflowContext;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundableOrderOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundableTicketOption;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.WorkflowInteractionView;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.RefundResolutionStatus;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行退票工作流中的订单定位、车票选择和草案边界校验。
 */
@Service
public class RefundWorkflowService {

    private final AgentWorkflowService workflowService;
    private final WorkflowInteractionTracker interactionTracker;
    private final ObjectMapper objectMapper;

    /**
     * 创建退票工作流服务。
     *
     * @param workflowService 通用工作流生命周期服务
     * @param interactionTracker 本轮结构化交互跟踪器
     * @param objectMapper 工作流上下文转换器
     */
    public RefundWorkflowService(
            AgentWorkflowService workflowService,
            WorkflowInteractionTracker interactionTracker,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.interactionTracker = interactionTracker;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据订单号、车次和日期定位可退订单，无法唯一确定时生成订单选择表单。
     *
     * @param requestContext 当前请求上下文
     * @param requestedOrderSn 用户提供的订单号
     * @param requestedTrainNumber 用户提供的车次号
     * @param requestedRidingDate 用户提供的乘车日期
     * @param requestedPassengerNames 用户提供的退票乘车人姓名
     * @param orders 当前账号订单列表
     * @return 订单定位结果
     */
    public RefundResolutionResult resolveOrder(
            AgentRequestContext requestContext,
            String requestedOrderSn,
            String requestedTrainNumber,
            String requestedRidingDate,
            List<String> requestedPassengerNames,
            List<RefundableOrderOption> orders) {
        String orderSn = normalize(requestedOrderSn);
        String trainNumber = normalize(requestedTrainNumber);
        String ridingDate = normalize(requestedRidingDate);
        List<String> passengerNames = normalizeNames(requestedPassengerNames);

        // 已完成订单选择的活动工作流直接恢复服务端结果，防止继续对话时重新开放其他订单。
        Optional<RefundResolutionResult> activeResolution = resolveActiveOrder(
                requestContext.userId(), requestContext.conversationId(), orderSn);
        if (activeResolution.isPresent()) {
            return activeResolution.get();
        }

        // 只允许下游明确标记为可退且具有订单号的本人订单进入候选集合。
        List<RefundableOrderOption> refundableOrders = orders == null ? List.of() : orders.stream()
                .filter(order -> order != null
                        && StringUtils.hasText(order.orderSn())
                        && Boolean.TRUE.equals(order.canRefund()))
                .collect(Collectors.toMap(
                        RefundableOrderOption::orderSn,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        if (refundableOrders.isEmpty()) {
            return result(RefundResolutionStatus.NO_REFUNDABLE_ORDERS, null, null, List.of(), null,
                    "当前账号没有可以退票的订单");
        }

        // 订单号优先精确匹配，未提供订单号时再按车次和日期收窄范围。
        List<RefundableOrderOption> matches = refundableOrders.stream()
                .filter(order -> orderSn == null || orderSn.equals(order.orderSn()))
                .filter(order -> trainNumber == null || trainNumber.equalsIgnoreCase(order.trainNumber()))
                .filter(order -> ridingDate == null || ridingDate.equals(order.ridingDate()))
                .toList();
        boolean uniquelyResolved = matches.size() == 1;
        RefundWorkflowContext context = new RefundWorkflowContext(
                orderSn, trainNumber, ridingDate, passengerNames, refundableOrders,
                uniquelyResolved ? matches.get(0).orderSn() : null, List.of(), List.of(), null);
        AgentWorkflowEntity workflow = createWorkflow(requestContext, context, uniquelyResolved);
        if (uniquelyResolved) {
            return result(RefundResolutionStatus.RESOLVED, workflow.getId(), matches.get(0), List.of(), null,
                    "已定位可退订单，继续解析该订单的可退车票");
        }

        // 条件未命中时展示全部可退订单，让用户显式决定目标。
        List<RefundableOrderOption> selectionOrders = matches.isEmpty() ? refundableOrders : matches;
        RefundOrderSelectionView selection = new RefundOrderSelectionView(
                workflow.getId(), workflow.getStage(),
                matches.isEmpty() && (orderSn != null || trainNumber != null || ridingDate != null)
                        ? "没有唯一匹配到你描述的订单，请从可退订单中选择"
                        : "请选择需要退票的订单",
                selectionOrders);
        interactionTracker.markRequired(requestContext.turnId(), selection);
        return result(RefundResolutionStatus.ORDER_SELECTION_REQUIRED, workflow.getId(), null, List.of(), null,
                "需要用户在本人可退订单列表中完成选择");
    }

    /**
     * 解析已确认订单的可退车票，并按用户提供的乘车人姓名自动匹配退票范围。
     *
     * @param requestContext 当前请求上下文
     * @param workflowId 工作流标识
     * @param order 已读取详情的本人订单
     * @param tickets 服务端预览返回的可退车票
     * @return 车票范围解析结果
     */
    public RefundResolutionResult resolveTickets(
            AgentRequestContext requestContext,
            String workflowId,
            RefundableOrderOption order,
            List<RefundableTicketOption> tickets) {
        AgentWorkflowEntity workflow = workflowService.findActiveById(requestContext.userId(), workflowId)
                .orElseThrow(() -> new IllegalArgumentException("退票工作流不存在或已经失效"));
        if (workflow.getWorkflowType() != WorkflowType.TICKET_REFUND) {
            throw new IllegalStateException("当前退票工作流不处于车票解析阶段");
        }
        RefundWorkflowContext context = readContext(workflow.getContextJson());
        if (order == null || !context.selectedOrderSn().equals(order.orderSn())) {
            throw new SecurityException("订单详情与服务端已确认订单不一致");
        }
        if (workflow.getStage() == WorkflowStage.CREATING_DRAFT) {
            // 重复调用解析器时直接恢复已确认范围，不重新开放车票候选项。
            Set<String> selectedIds = Set.copyOf(context.selectedOrderItemIds());
            List<RefundableTicketOption> selectedTickets = context.ticketOptions().stream()
                    .filter(ticket -> selectedIds.contains(ticket.orderItemId()))
                    .toList();
            return result(RefundResolutionStatus.RESOLVED, workflowId, order, selectedTickets,
                    context.refundType(), "已恢复服务端确认的退票范围");
        }
        if (workflow.getStage() != WorkflowStage.SELECTING_REFUND_TICKETS) {
            throw new IllegalStateException("当前退票工作流不处于车票解析阶段");
        }

        // 预览结果是唯一可信的可退范围，同时按子订单标识去重。
        List<RefundableTicketOption> refundableTickets = tickets == null ? List.of() : tickets.stream()
                .filter(ticket -> ticket != null && StringUtils.hasText(ticket.orderItemId()))
                .collect(Collectors.toMap(
                        RefundableTicketOption::orderItemId,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        if (refundableTickets.isEmpty()) {
            return result(RefundResolutionStatus.NO_REFUNDABLE_TICKETS, workflowId, order, List.of(), null,
                    "该订单当前没有可以退票的车票");
        }

        // 每个姓名只有唯一车票时才允许自动匹配，否则必须让用户查看明细后勾选。
        List<RefundableTicketOption> selected = matchTicketsByName(
                context.requestedPassengerNames(), refundableTickets);
        boolean resolved = !context.requestedPassengerNames().isEmpty()
                && selected.size() == context.requestedPassengerNames().size();
        if (!resolved) {
            RefundWorkflowContext updatedContext = withTickets(context, refundableTickets, List.of(), null);
            workflowService.updateContext(
                    requestContext.userId(), workflowId, WorkflowStage.SELECTING_REFUND_TICKETS,
                    writeContext(updatedContext));
            RefundTicketSelectionView selection = new RefundTicketSelectionView(
                    workflowId, WorkflowStage.SELECTING_REFUND_TICKETS,
                    context.requestedPassengerNames().isEmpty()
                            ? "请选择需要退票的乘车人车票"
                            : "乘车人姓名未能唯一匹配，请选择需要退票的车票",
                    context.selectedOrderSn(), refundableTickets);
            interactionTracker.markRequired(requestContext.turnId(), selection);
            return result(RefundResolutionStatus.TICKET_SELECTION_REQUIRED, workflowId, order, List.of(), null,
                    "需要用户在可退车票列表中完成选择");
        }

        // 服务端根据选择是否覆盖全部可退车票确定全部或部分退票类型。
        List<String> selectedIds = selected.stream()
                .map(RefundableTicketOption::orderItemId)
                .sorted()
                .toList();
        int refundType = selectedIds.size() == refundableTickets.size() ? 1 : 0;
        RefundWorkflowContext updatedContext = withTickets(context, refundableTickets, selectedIds, refundType);
        workflowService.advance(
                requestContext.userId(), workflowId, WorkflowStage.SELECTING_REFUND_TICKETS,
                WorkflowStage.CREATING_DRAFT, writeContext(updatedContext));
        return result(RefundResolutionStatus.RESOLVED, workflowId, order, selected, refundType,
                "已确认退票范围，可以继续生成退票草案");
    }

    /**
     * 提交退票工作流中的订单选择并推进到车票解析阶段。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param request 订单选择请求
     * @return 已校验订单和下一阶段
     */
    public RefundOrderSelectionResult selectOrder(
            String userId,
            String workflowId,
            RefundOrderSelectionRequest request) {
        AgentWorkflowEntity workflow = requireWorkflow(userId, workflowId, WorkflowStage.SELECTING_REFUND_ORDER);
        String orderSn = request == null ? null : normalize(request.orderSn());
        if (orderSn == null) {
            throw new IllegalArgumentException("请选择需要退票的订单");
        }
        RefundWorkflowContext context = readContext(workflow.getContextJson());

        // 订单号必须来自服务端保存的本人可退候选集合。
        RefundableOrderOption selected = context.orderOptions().stream()
                .filter(order -> orderSn.equals(order.orderSn()) && Boolean.TRUE.equals(order.canRefund()))
                .findFirst()
                .orElseThrow(() -> new SecurityException("选择中包含不属于当前候选集的订单"));
        RefundWorkflowContext updatedContext = new RefundWorkflowContext(
                context.requestedOrderSn(), context.requestedTrainNumber(), context.requestedRidingDate(),
                context.requestedPassengerNames(), context.orderOptions(), selected.orderSn(),
                List.of(), List.of(), null);
        AgentWorkflowEntity updated = workflowService.advance(
                userId, workflowId, WorkflowStage.SELECTING_REFUND_ORDER,
                WorkflowStage.SELECTING_REFUND_TICKETS, writeContext(updatedContext));
        return new RefundOrderSelectionResult(updated.getId(), updated.getStage(), selected.orderSn());
    }

    /**
     * 提交用户勾选的可退车票并推进到草案创建阶段。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param request 车票选择请求
     * @return 已校验退票范围和退款类型
     */
    public RefundTicketSelectionResult selectTickets(
            String userId,
            String workflowId,
            RefundTicketSelectionRequest request) {
        AgentWorkflowEntity workflow = requireWorkflow(userId, workflowId, WorkflowStage.SELECTING_REFUND_TICKETS);
        RefundWorkflowContext context = readContext(workflow.getContextJson());
        List<String> selectedIds = request == null || request.orderItemIds() == null ? List.of()
                : request.orderItemIds().stream().map(this::normalize).filter(StringUtils::hasText)
                        .distinct().sorted().toList();
        if (selectedIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一张需要退票的车票");
        }

        // 浏览器提交的每个标识都必须存在于服务端预览保存的可退车票集合中。
        Set<String> allowedIds = context.ticketOptions().stream()
                .map(RefundableTicketOption::orderItemId)
                .collect(Collectors.toSet());
        if (!allowedIds.containsAll(selectedIds)) {
            throw new SecurityException("选择中包含不属于当前可退范围的车票");
        }
        int refundType = selectedIds.size() == allowedIds.size() ? 1 : 0;
        RefundWorkflowContext updatedContext = withTickets(
                context, context.ticketOptions(), selectedIds, refundType);
        AgentWorkflowEntity updated = workflowService.advance(
                userId, workflowId, WorkflowStage.SELECTING_REFUND_TICKETS,
                WorkflowStage.CREATING_DRAFT, writeContext(updatedContext));
        return new RefundTicketSelectionResult(
                updated.getId(), updated.getStage(), context.selectedOrderSn(), selectedIds, refundType);
    }

    /**
     * 恢复会话中等待用户选择的退票订单或车票表单。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @return 可恢复的退票交互视图
     */
    public Optional<WorkflowInteractionView> findPendingSelection(String userId, String conversationId) {
        return workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.TICKET_REFUND)
                .flatMap(workflow -> pendingView(workflow));
    }

    /**
     * 生成供回答模型继续退票链路的服务端工作流提示。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @return 不含敏感字段的活动工作流提示
     */
    public Optional<String> activeWorkflowPrompt(String userId, String conversationId) {
        return workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.TICKET_REFUND)
                .map(workflow -> "当前退票工作流由服务端维护，workflowId=" + workflow.getId()
                        + "，stage=" + workflow.getStage().name() + "，context=" + workflow.getContextJson()
                        + "。SELECTING_REFUND_ORDER 时等待用户选择；SELECTING_REFUND_TICKETS 且 ticketOptions 为空时，"
                        + "必须使用 selectedOrderSn 再次调用 resolve_ticket_refund；ticketOptions 非空时等待用户选择。"
                        + "CREATING_DRAFT 时只能使用已保存的 orderSn、refundType 和 selectedOrderItemIds 创建草案。");
    }

    /**
     * 校验退票草案严格使用服务端已确认的订单、类型和车票范围。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @param orderSn 草案订单号
     * @param refundType 草案退款类型
     * @param orderItemIds 草案车票范围
     */
    public void validateDraft(
            String userId,
            String conversationId,
            String orderSn,
            Integer refundType,
            List<String> orderItemIds) {
        AgentWorkflowEntity workflow = workflowService.findActive(userId, conversationId)
                .filter(candidate -> candidate.getWorkflowType() == WorkflowType.TICKET_REFUND)
                .orElseThrow(() -> new IllegalStateException("退票草案缺少有效的服务端工作流"));
        if (workflow.getStage() != WorkflowStage.CREATING_DRAFT) {
            throw new IllegalStateException("当前退票工作流尚未进入草案创建阶段");
        }
        RefundWorkflowContext context = readContext(workflow.getContextJson());
        List<String> normalizedIds = orderItemIds == null ? List.of() : orderItemIds.stream()
                .map(this::normalize).filter(StringUtils::hasText).distinct().sorted().toList();
        if (Integer.valueOf(1).equals(refundType) && normalizedIds.isEmpty()) {
            // 全部退票允许草案工具使用空列表表达整个服务端已确认范围。
            normalizedIds = context.selectedOrderItemIds();
        }

        // 三项关键参数必须与服务端解析或用户勾选结果完全一致。
        if (!context.selectedOrderSn().equals(normalize(orderSn))
                || !context.refundType().equals(refundType)
                || !context.selectedOrderItemIds().equals(normalizedIds)) {
            throw new SecurityException("退票草案范围与服务端已确认结果不一致");
        }
    }

    /**
     * 退票草案持久化成功后完成当前会话的退票工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     */
    public void completeAfterDraft(String userId, String conversationId) {
        workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.TICKET_REFUND)
                .ifPresent(workflow -> workflowService.complete(
                        userId, workflow.getId(), workflow.getStage(), workflow.getContextJson()));
    }

    /**
     * 查找已经完成订单选择且等待详情解析的订单号。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @return 已确认订单号
     */
    public Optional<String> selectedOrderForResolution(String userId, String conversationId) {
        return workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.TICKET_REFUND)
                .filter(workflow -> workflow.getStage() == WorkflowStage.SELECTING_REFUND_TICKETS)
                .map(workflow -> readContext(workflow.getContextJson()).selectedOrderSn())
                .filter(StringUtils::hasText);
    }

    /**
     * 创建或恢复退票订单选择工作流，并在唯一匹配时推进到车票解析阶段。
     *
     * @param requestContext 当前请求上下文
     * @param context 待保存的退票上下文
     * @param resolved 是否已经唯一定位订单
     * @return 当前阶段的持久化工作流
     */
    private AgentWorkflowEntity createWorkflow(
            AgentRequestContext requestContext,
            RefundWorkflowContext context,
            boolean resolved) {
        String contextJson = writeContext(context);
        AgentWorkflowEntity workflow = workflowService.startOrResume(
                requestContext.userId(), requestContext.conversationId(), WorkflowType.TICKET_REFUND,
                WorkflowStage.SELECTING_REFUND_ORDER, contextJson);
        if (workflow.getStage() != WorkflowStage.SELECTING_REFUND_ORDER) {
            throw new IllegalStateException("当前退票工作流已经完成订单选择");
        }
        if (!resolved) {
            return workflowService.updateContext(
                    requestContext.userId(), workflow.getId(), WorkflowStage.SELECTING_REFUND_ORDER, contextJson);
        }
        return workflowService.advance(
                requestContext.userId(), workflow.getId(), WorkflowStage.SELECTING_REFUND_ORDER,
                WorkflowStage.SELECTING_REFUND_TICKETS, contextJson);
    }

    /**
     * 恢复已经完成订单选择的退票工作流结果。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @param requestedOrderSn 本轮明确提供的订单号
     * @return 已恢复的订单解析结果
     */
    private Optional<RefundResolutionResult> resolveActiveOrder(
            String userId,
            String conversationId,
            String requestedOrderSn) {
        return workflowService.findActive(userId, conversationId)
                .filter(workflow -> workflow.getWorkflowType() == WorkflowType.TICKET_REFUND)
                .filter(workflow -> workflow.getStage() == WorkflowStage.SELECTING_REFUND_TICKETS
                        || workflow.getStage() == WorkflowStage.CREATING_DRAFT)
                .map(workflow -> {
                    RefundWorkflowContext context = readContext(workflow.getContextJson());
                    if (requestedOrderSn != null && !requestedOrderSn.equals(context.selectedOrderSn())) {
                        throw new SecurityException("请求订单与退票工作流已确认订单不一致");
                    }
                    RefundableOrderOption selected = context.orderOptions().stream()
                            .filter(order -> context.selectedOrderSn().equals(order.orderSn()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("退票工作流缺少已确认订单"));
                    return result(RefundResolutionStatus.RESOLVED, workflow.getId(), selected, List.of(),
                            context.refundType(), "已恢复服务端确认的退票订单");
                });
    }

    /**
     * 按姓名唯一匹配可退车票，任一姓名存在歧义时返回空列表。
     *
     * @param requestedNames 用户提供的乘车人姓名
     * @param tickets 当前订单可退车票
     * @return 唯一匹配的车票集合
     */
    private List<RefundableTicketOption> matchTicketsByName(
            List<String> requestedNames,
            List<RefundableTicketOption> tickets) {
        Map<String, List<RefundableTicketOption>> ticketsByName = tickets.stream()
                .filter(ticket -> StringUtils.hasText(ticket.realName()))
                .collect(Collectors.groupingBy(RefundableTicketOption::realName));
        List<RefundableTicketOption> selected = new ArrayList<>();
        for (String name : requestedNames) {
            List<RefundableTicketOption> matches = ticketsByName.getOrDefault(name, List.of());
            if (matches.size() != 1) {
                return List.of();
            }
            selected.add(matches.get(0));
        }
        return selected.stream().distinct().toList();
    }

    /**
     * 按指定阶段加载当前用户的退票工作流。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param stage 预期阶段
     * @return 已校验工作流
     */
    private AgentWorkflowEntity requireWorkflow(String userId, String workflowId, WorkflowStage stage) {
        AgentWorkflowEntity workflow = workflowService.findActiveById(userId, workflowId)
                .orElseThrow(() -> new IllegalArgumentException("退票工作流不存在或已经失效"));
        if (workflow.getWorkflowType() != WorkflowType.TICKET_REFUND || workflow.getStage() != stage) {
            throw new IllegalStateException("当前退票工作流阶段已经变化");
        }
        return workflow;
    }

    /**
     * 将持久化工作流转换成当前阶段对应的前端交互视图。
     *
     * @param workflow 当前持久化工作流
     * @return 当前阶段可展示的交互视图
     */
    private Optional<WorkflowInteractionView> pendingView(AgentWorkflowEntity workflow) {
        RefundWorkflowContext context = readContext(workflow.getContextJson());
        if (workflow.getStage() == WorkflowStage.SELECTING_REFUND_ORDER) {
            return Optional.of(new RefundOrderSelectionView(
                    workflow.getId(), workflow.getStage(), "请选择需要退票的订单", context.orderOptions()));
        }
        if (workflow.getStage() == WorkflowStage.SELECTING_REFUND_TICKETS
                && context.ticketOptions() != null && !context.ticketOptions().isEmpty()) {
            return Optional.of(new RefundTicketSelectionView(
                    workflow.getId(), workflow.getStage(), "请选择需要退票的乘车人车票",
                    context.selectedOrderSn(), context.ticketOptions()));
        }
        return Optional.empty();
    }

    /**
     * 复制退票上下文并替换可退车票和选择结果。
     *
     * @param context 原退票上下文
     * @param tickets 可退车票列表
     * @param selectedIds 已选择车票标识
     * @param refundType 退款类型
     * @return 更新后的退票上下文
     */
    private RefundWorkflowContext withTickets(
            RefundWorkflowContext context,
            List<RefundableTicketOption> tickets,
            List<String> selectedIds,
            Integer refundType) {
        return new RefundWorkflowContext(
                context.requestedOrderSn(), context.requestedTrainNumber(), context.requestedRidingDate(),
                context.requestedPassengerNames(), context.orderOptions(), context.selectedOrderSn(),
                tickets, selectedIds, refundType);
    }

    /**
     * 创建统一的退票解析结果。
     *
     * @param status 解析状态
     * @param workflowId 工作流标识
     * @param order 已确认订单
     * @param tickets 已确认车票
     * @param refundType 退款类型
     * @param message 下一步说明
     * @return 退票解析结果
     */
    private RefundResolutionResult result(
            RefundResolutionStatus status,
            String workflowId,
            RefundableOrderOption order,
            List<RefundableTicketOption> tickets,
            Integer refundType,
            String message) {
        return new RefundResolutionResult(status, workflowId, order, tickets, refundType, message);
    }

    /**
     * 规范化用户提供的乘车人姓名并保持输入顺序。
     *
     * @param values 原始姓名列表
     * @return 去空白且去重后的姓名列表
     */
    private List<String> normalizeNames(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(this::normalize)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    /**
     * 序列化退票工作流上下文。
     *
     * @param context 退票工作流上下文
     * @return JSON 文本
     */
    private String writeContext(RefundWorkflowContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存退票工作流上下文", exception);
        }
    }

    /**
     * 反序列化退票工作流上下文。
     *
     * @param contextJson 工作流上下文 JSON
     * @return 退票工作流上下文
     */
    private RefundWorkflowContext readContext(String contextJson) {
        try {
            return objectMapper.readValue(contextJson, RefundWorkflowContext.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("退票工作流上下文已损坏", exception);
        }
    }

    /**
     * 规范化允许为空的用户或模型文本。
     *
     * @param value 原始文本
     * @return 去除首尾空白的文本或 null
     */
    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
