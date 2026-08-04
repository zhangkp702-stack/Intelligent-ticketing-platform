package org.opengoofy.index12306.ai.agentservice.chat.controller;

import org.opengoofy.index12306.ai.agentservice.chat.service.AgentChatService;
import org.opengoofy.index12306.ai.agentservice.chat.exception.AgentChatException;


import jakarta.servlet.http.HttpServletResponse;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ConversationPage;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.CreateConversationRequest;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.CreateConversationResponse;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.HistoryMessagePage;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.PrepareTurnResponse;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.SubmitTurnRequest;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.TurnStatusView;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.RecoverableActionView;
import org.opengoofy.index12306.ai.agentservice.action.service.PurchaseActionService;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationHistoryService;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationDeletionService;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.PurchaseWorkflowModels.PassengerSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.service.PurchaseWorkflowService;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.CancellationWorkflowModels.OrderSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.WorkflowInteractionView;
import org.opengoofy.index12306.ai.agentservice.workflow.service.CancellationWorkflowService;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundOrderSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundOrderSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundOrderSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundTicketSelectionRequest;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundTicketSelectionResult;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.RefundWorkflowModels.RefundTicketSelectionView;
import org.opengoofy.index12306.ai.agentservice.workflow.service.RefundWorkflowService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 为网关认证后的用户提供会话创建和 SSE 流式回答接口。
 */
@RestController
@RequestMapping("/api/agent-service")
public class AgentChatController {

    private static final String USER_ID_HEADER = "userId";
    private static final String USERNAME_HEADER = "username";
    private static final String ATTEMPT_ID_HEADER = "X-Attempt-Id";
    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";

    private final AgentChatService agentChatService;
    private final ConversationHistoryService conversationHistoryService;
    private final ConversationDeletionService conversationDeletionService;
    private final PurchaseActionService purchaseActionService;
    private final PurchaseWorkflowService purchaseWorkflowService;
    private final CancellationWorkflowService cancellationWorkflowService;
    private final RefundWorkflowService refundWorkflowService;

    /**
     * 创建智能体对话控制器。
     *
     * @param agentChatService 对话编排服务
     * @param conversationHistoryService 会话历史查询服务
     * @param conversationDeletionService 会话删除服务
     * @param purchaseActionService 高风险操作恢复服务
     * @param purchaseWorkflowService 购票工作流服务
     * @param cancellationWorkflowService 取消订单工作流服务
     * @param refundWorkflowService 退票工作流服务
     */
    public AgentChatController(
            AgentChatService agentChatService,
            ConversationHistoryService conversationHistoryService,
            ConversationDeletionService conversationDeletionService,
            PurchaseActionService purchaseActionService,
            PurchaseWorkflowService purchaseWorkflowService,
            CancellationWorkflowService cancellationWorkflowService,
            RefundWorkflowService refundWorkflowService) {
        this.agentChatService = agentChatService;
        this.conversationHistoryService = conversationHistoryService;
        this.conversationDeletionService = conversationDeletionService;
        this.purchaseActionService = purchaseActionService;
        this.purchaseWorkflowService = purchaseWorkflowService;
        this.cancellationWorkflowService = cancellationWorkflowService;
        this.refundWorkflowService = refundWorkflowService;
    }

    /**
     * 为当前认证用户创建新的对话会话。
     *
     * @param userId 网关注入的用户标识
     * @param request 可选标题请求
     * @return 新会话标识
     */
    @PostMapping("/conversations")
    public CreateConversationResponse createConversation(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestBody(required = false) CreateConversationRequest request) {
        // 会话所有者完全来自网关认证头，不接受请求体覆盖用户身份。
        String title = request == null ? null : request.title();
        return new CreateConversationResponse(agentChatService.createConversation(userId, title));
    }

    /**
     * 为当前认证用户在指定会话中预创建服务端轮次。
     *
     * @param userId 网关注入的用户标识
     * @param conversationId 会话标识
     * @return 服务端轮次标识和首次提交凭证
     */
    @PostMapping("/conversations/{conversationId}/turns")
    public PrepareTurnResponse prepareTurn(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId) {
        // 轮次和令牌完全由服务端生成，前端只保存并在首次提交或网络重试时回传。
        return agentChatService.prepareTurn(userId, conversationId);
    }

    /**
     * 查询当前认证用户指定轮次的持久化状态和最终回答。
     *
     * @param userId 网关注入的用户标识
     * @param turnId 服务端轮次标识
     * @return 当前轮次状态和已完成结果
     */
    @GetMapping("/turns/{turnId}")
    public TurnStatusView getTurn(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String turnId) {
        // 查询结果来自数据库终态，不依赖发起请求的原服务实例或 SSE 连接。
        return agentChatService.getTurn(userId, turnId);
    }

    /**
     * 分页查询当前认证用户自己的智能体会话。
     *
     * @param userId 网关注入的用户标识
     * @param current 当前页码
     * @param size 每页数量
     * @return 按最近更新时间倒序排列的会话分页
     */
    @GetMapping("/conversations")
    public ConversationPage listConversations(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {
        // 用户标识只来自网关认证头，查询层会再次按 userId 收敛数据范围。
        return conversationHistoryService.listConversations(userId, current, size);
    }

    /**
     * 使用消息序号游标查询当前用户会话的文本消息历史。
     *
     * @param userId 网关注入的用户标识
     * @param conversationId 会话标识
     * @param beforeSequence 可选消息序号上界
     * @param size 返回数量
     * @return 按序号升序排列的历史消息
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public HistoryMessagePage listMessages(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId,
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam(defaultValue = "50") int size) {
        // 工具调用和内部结构化消息不会通过历史接口返回浏览器。
        return conversationHistoryService.listMessages(
                userId, conversationId, beforeSequence, size);
    }

    /**
     * 删除当前认证用户拥有的会话及其关联数据。
     *
     * @param userId 网关注入的用户标识
     * @param conversationId 会话标识
     * @return 删除完成后的空响应
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId) {
        // 用户标识仅来自网关认证头，服务层会锁定会话后再次校验归属。
        conversationDeletionService.deleteConversation(userId, conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 恢复会话最近的操作卡片，并在仍可确认时重新签发一次确认视图。
     *
     * @param userId 网关注入的用户标识
     * @param conversationId 会话标识
     * @return 最近操作；会话没有操作时返回 204
     */
    @GetMapping("/conversations/{conversationId}/pending-action")
    public ResponseEntity<RecoverableActionView> recoverPendingAction(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId) {
        // 恢复接口不读取数据库中的令牌明文，令牌由操作服务根据当前状态重新签发。
        return purchaseActionService.recoverLatestAction(userId, conversationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 恢复会话中尚未提交的乘车人选择表单。
     *
     * @param userId 网关注入的用户标识
     * @param conversationId 会话标识
     * @return 等待选择的购票工作流；不存在时返回 204
     */
    @GetMapping("/conversations/{conversationId}/pending-workflow")
    public ResponseEntity<WorkflowInteractionView> recoverPendingWorkflow(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId) {
        // 候选列表从当前用户的持久化工作流恢复，不读取其他会话或账号的数据。
        return purchaseWorkflowService.findPendingSelection(userId, conversationId)
                .map(WorkflowInteractionView.class::cast)
                .or(() -> cancellationWorkflowService.findPendingSelection(userId, conversationId)
                        .map(WorkflowInteractionView.class::cast))
                .or(() -> refundWorkflowService.findPendingSelection(userId, conversationId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 提交购票工作流中的乘车人勾选结果。
     *
     * @param userId 网关注入的用户标识
     * @param conversationId 会话标识
     * @param workflowId 工作流标识
     * @param request 用户勾选的乘车人标识
     * @return 已校验的选择结果和下一阶段
     */
    @PostMapping("/conversations/{conversationId}/workflows/{workflowId}/passengers")
    public PassengerSelectionResult selectWorkflowPassengers(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId,
            @PathVariable String workflowId,
            @RequestBody PassengerSelectionRequest request) {
        // 先按会话恢复工作流，避免只凭 workflowId 跨会话提交选择。
        PassengerSelectionView pending = purchaseWorkflowService
                .findPendingSelection(userId, conversationId)
                .filter(view -> view.workflowId().equals(workflowId))
                .orElseThrow(() -> new AgentChatException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "WORKFLOW_NOT_SELECTABLE",
                        "当前会话没有可提交的乘车人选择"));
        try {
            return purchaseWorkflowService.selectPassengers(userId, pending.workflowId(), request);
        } catch (SecurityException exception) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "INVALID_PASSENGER_SELECTION",
                    "乘车人选择无效");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "WORKFLOW_STATE_CHANGED",
                    exception.getMessage());
        }
    }

    /**
     * 提交取消订单工作流中的订单选择结果。
     *
     * @param userId 网关注入的用户标识
     * @param conversationId 会话标识
     * @param workflowId 工作流标识
     * @param request 用户选择的订单号
     * @return 已校验的订单和下一阶段
     */
    @PostMapping("/conversations/{conversationId}/workflows/{workflowId}/order")
    public OrderSelectionResult selectWorkflowOrder(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId,
            @PathVariable String workflowId,
            @RequestBody OrderSelectionRequest request) {
        // 先按当前会话恢复选择视图，禁止只凭工作流标识跨会话提交订单。
        OrderSelectionView pending = cancellationWorkflowService
                .findPendingSelection(userId, conversationId)
                .filter(view -> view.workflowId().equals(workflowId))
                .orElseThrow(() -> new AgentChatException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "WORKFLOW_NOT_SELECTABLE",
                        "当前会话没有可提交的订单选择"));
        try {
            return cancellationWorkflowService.selectOrder(userId, pending.workflowId(), request);
        } catch (SecurityException exception) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "INVALID_ORDER_SELECTION",
                    "订单选择无效");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "WORKFLOW_STATE_CHANGED",
                    exception.getMessage());
        }
    }

    /**
     * 提交退票工作流中的订单选择结果。
     *
     * @param userId 网关注入的用户标识
     * @param conversationId 会话标识
     * @param workflowId 工作流标识
     * @param request 用户选择的可退订单号
     * @return 已校验订单和下一阶段
     */
    @PostMapping("/conversations/{conversationId}/workflows/{workflowId}/refund-order")
    public RefundOrderSelectionResult selectRefundOrder(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId,
            @PathVariable String workflowId,
            @RequestBody RefundOrderSelectionRequest request) {
        // 先从当前会话恢复退票订单表单，禁止跨会话提交工作流选择。
        WorkflowInteractionView pending = refundWorkflowService.findPendingSelection(userId, conversationId)
                .filter(view -> view.workflowId().equals(workflowId))
                .orElseThrow(() -> new AgentChatException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "WORKFLOW_NOT_SELECTABLE",
                        "当前会话没有可提交的退票订单选择"));
        if (!(pending instanceof RefundOrderSelectionView)) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "WORKFLOW_STATE_CHANGED",
                    "当前退票工作流不处于订单选择阶段");
        }
        try {
            return refundWorkflowService.selectOrder(userId, workflowId, request);
        } catch (SecurityException exception) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "INVALID_REFUND_ORDER_SELECTION",
                    "退票订单选择无效");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "WORKFLOW_STATE_CHANGED",
                    exception.getMessage());
        }
    }

    /**
     * 提交退票工作流中的车票勾选结果。
     *
     * @param userId 网关注入的用户标识
     * @param conversationId 会话标识
     * @param workflowId 工作流标识
     * @param request 用户勾选的可退车票标识
     * @return 已校验退票范围、类型和下一阶段
     */
    @PostMapping("/conversations/{conversationId}/workflows/{workflowId}/refund-tickets")
    public RefundTicketSelectionResult selectRefundTickets(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId,
            @PathVariable String workflowId,
            @RequestBody RefundTicketSelectionRequest request) {
        // 车票候选项必须来自当前会话已保存的退票预览结果。
        WorkflowInteractionView pending = refundWorkflowService.findPendingSelection(userId, conversationId)
                .filter(view -> view.workflowId().equals(workflowId))
                .orElseThrow(() -> new AgentChatException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "WORKFLOW_NOT_SELECTABLE",
                        "当前会话没有可提交的退票车票选择"));
        if (!(pending instanceof RefundTicketSelectionView)) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "WORKFLOW_STATE_CHANGED",
                    "当前退票工作流不处于车票选择阶段");
        }
        try {
            return refundWorkflowService.selectTickets(userId, workflowId, request);
        } catch (SecurityException exception) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "INVALID_REFUND_TICKET_SELECTION",
                    "退票车票选择无效");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "WORKFLOW_STATE_CHANGED",
                    exception.getMessage());
        }
    }

    /**
     * 完成一轮对话并按 META、DELTA、DONE 或 ERROR 事件流式返回。
     *
     * @param userId 网关注入的用户标识
     * @param username 网关注入的用户名
     * @param turnId 服务端预创建轮次标识
     * @param attemptId 可选网络尝试标识
     * @param lastEventId 可选的客户端最后确认事件序号
     * @param request 用户问题
     * @param response Servlet 响应，用于关闭代理缓冲
     * @return 会逐事件刷新到客户端的 SSE 发射器
     */
    @PostMapping(value = "/turns/{turnId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestHeader(USERNAME_HEADER) String username,
            @PathVariable String turnId,
            @RequestHeader(value = ATTEMPT_ID_HEADER, required = false) String attemptId,
            @RequestHeader(value = LAST_EVENT_ID_HEADER, required = false) String lastEventId,
            @RequestBody SubmitTurnRequest request,
            HttpServletResponse response) {
        ChatCommand command = buildCommand(userId, username, turnId, attemptId, request);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean connectionOpen = new AtomicBoolean(true);

        // 禁止网关或反向代理转换、压缩和缓冲 SSE，确保每个 DELTA 都能立即抵达浏览器。
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        // 携带 Last-Event-ID 时只读取持久化日志；首次连接才启动业务流水线。
        Flux<ChatEvent> eventStream = hasText(lastEventId)
                ? agentChatService.resume(command, parseLastEventId(lastEventId))
                : agentChatService.stream(command);
        eventStream
                // SSE 只发送已经持久化并获得事件序号的协议对象。
                .subscribe(
                        event -> sendEvent(emitter, event, connectionOpen),
                        exception -> completeEmitter(emitter, connectionOpen, exception),
                        () -> completeEmitter(emitter, connectionOpen, null));

        // HTTP 生命周期只控制传输连接；业务任务继续运行并写入事件日志，供同轮次重连恢复。
        emitter.onCompletion(() -> connectionOpen.set(false));
        emitter.onTimeout(() -> {
            if (connectionOpen.compareAndSet(true, false)) {
                emitter.complete();
            }
        });
        emitter.onError(ignored -> connectionOpen.set(false));
        return emitter;
    }

    /**
     * 把单个已经持久化的对话事件立即写入仍然有效的 SSE 响应。
     *
     * @param emitter 当前 HTTP 连接的 SSE 发射器
     * @param event 待发送的对话事件
     * @param connectionOpen 当前传输连接是否仍可写
     */
    private void sendEvent(
            SseEmitter emitter,
            ChatEvent event,
            AtomicBoolean connectionOpen) {
        if (!connectionOpen.get()) {
            return;
        }
        try {
            // SSE id 使用轮次内单调序号，浏览器重连时可准确声明已确认边界。
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .name(event.type().name().toLowerCase(java.util.Locale.ROOT))
                    .data(event, MediaType.APPLICATION_JSON);
            if (event.eventSequence() != null) {
                builder.id(Long.toString(event.eventSequence()));
            }
            emitter.send(builder);
        } catch (Exception exception) {
            // 写失败仅关闭当前传输；订阅继续消费并持久化后续事件。
            completeEmitter(emitter, connectionOpen, exception);
        }
    }

    /**
     * 原子结束当前 SSE 连接，避免网络错误和业务完成重复操作发射器。
     *
     * @param emitter 当前 HTTP 连接的 SSE 发射器
     * @param connectionOpen 当前传输连接是否仍可写
     * @param exception 可选传输或执行异常
     */
    private void completeEmitter(
            SseEmitter emitter,
            AtomicBoolean connectionOpen,
            Throwable exception) {
        // CAS 保证完成、超时和写失败并发发生时只关闭一次 Servlet 异步请求。
        if (!connectionOpen.compareAndSet(true, false)) {
            return;
        }
        if (exception == null) {
            emitter.complete();
        } else {
            emitter.completeWithError(exception);
        }
    }

    /**
     * 解析客户端最后确认的 SSE 事件序号。
     *
     * @param lastEventId Last-Event-ID 请求头原值
     * @return 非负事件序号
     */
    private long parseLastEventId(String lastEventId) {
        try {
            long sequence = Long.parseLong(lastEventId.trim());
            if (sequence < 0L) {
                throw new NumberFormatException("negative sequence");
            }
            return sequence;
        } catch (NumberFormatException exception) {
            // 非法游标不能退化为首次执行，否则可能绕过断线续传语义触发重复订阅。
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_LAST_EVENT_ID",
                    "Last-Event-ID 必须是非负整数");
        }
    }

    /**
     * 取消当前用户指定的流式生成任务。
     *
     * @param userId 网关注入的用户标识
     * @param turnId 服务端轮次标识
     * @return 无内容响应，取消结果由服务端轮次状态保证
     */
    @PostMapping("/turns/{turnId}/cancel")
    public ResponseEntity<Void> cancel(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String turnId) {
        // 取消操作按服务端轮次定位，重复取消保持无副作用的成功响应。
        agentChatService.cancel(userId, turnId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 使用服务端轮次、认证头和当前网络尝试构造不可变对话命令。
     *
     * @param userId 用户标识
     * @param username 用户名
     * @param turnId 服务端轮次标识
     * @param attemptId 可选网络尝试标识
     * @param request 请求体
     * @return 完整对话命令
     */
    private ChatCommand buildCommand(
            String userId,
            String username,
            String turnId,
            String attemptId,
            SubmitTurnRequest request) {
        if (request == null) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "请求体不能为空");
        }

        // attemptId 只用于网络链路观测，可以每次变化；业务幂等始终由服务端 turnId 决定。
        String actualAttemptId = hasText(attemptId) ? attemptId.trim() : randomAttemptId();
        return new ChatCommand(
                turnId, actualAttemptId, request.submissionToken(), userId, username,
                request.conversationId(), request.message());
    }

    /**
     * 判断外部头字段是否包含有效文本。
     *
     * @param value 原始字段值
     * @return 包含非空白字符时返回 true
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 生成符合日志字段长度约束的网络尝试标识。
     *
     * @return 32 位无分隔符 UUID
     */
    private String randomAttemptId() {
        // 网络尝试 ID 不参与 Turn、Task 或 Action 的业务幂等判断。
        return UUID.randomUUID().toString().replace("-", "");
    }
}
