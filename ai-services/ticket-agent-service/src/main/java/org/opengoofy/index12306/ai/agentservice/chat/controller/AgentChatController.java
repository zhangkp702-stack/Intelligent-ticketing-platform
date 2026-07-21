package org.opengoofy.index12306.ai.agentservice.chat.controller;

import org.opengoofy.index12306.ai.agentservice.chat.service.AgentChatService;
import org.opengoofy.index12306.ai.agentservice.chat.exception.AgentChatException;


import jakarta.servlet.http.HttpServletResponse;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCancelRequest;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatRequest;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatResult;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ConversationPage;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.CreateConversationRequest;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.CreateConversationResponse;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.HistoryMessagePage;
import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.RecoverableActionView;
import org.opengoofy.index12306.ai.agentservice.action.service.PurchaseActionService;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationHistoryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 为网关认证后的用户提供会话创建、普通回答和 SSE 流式回答接口。
 */
@RestController
@RequestMapping("/api/agent-service")
public class AgentChatController {

    private static final String USER_ID_HEADER = "userId";
    private static final String USERNAME_HEADER = "username";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final AgentChatService agentChatService;
    private final ConversationHistoryService conversationHistoryService;
    private final PurchaseActionService purchaseActionService;

    /**
     * 创建智能体对话控制器。
     *
     * @param agentChatService 对话编排服务
     * @param conversationHistoryService 会话历史查询服务
     * @param purchaseActionService 高风险操作恢复服务
     */
    public AgentChatController(
            AgentChatService agentChatService,
            ConversationHistoryService conversationHistoryService,
            PurchaseActionService purchaseActionService) {
        this.agentChatService = agentChatService;
        this.conversationHistoryService = conversationHistoryService;
        this.purchaseActionService = purchaseActionService;
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
     * 完成一轮对话并以普通 JSON 返回完整回答。
     *
     * @param userId 网关注入的用户标识
     * @param username 网关注入的用户名
     * @param requestId 可选请求标识
     * @param idempotencyKey 可选幂等键
     * @param request 用户问题
     * @return 最终完整回答
     */
    @PostMapping("/chat")
    public Mono<ChatResult> chat(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestHeader(value = USERNAME_HEADER, required = false) String username,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ChatRequest request) {
        // 普通接口与 SSE 接口共用同一编排链和数据库幂等语义。
        return agentChatService.chat(buildCommand(
                userId, username, requestId, idempotencyKey, request));
    }

    /**
     * 完成一轮对话并按 META、DELTA、DONE 或 ERROR 事件流式返回。
     *
     * @param userId 网关注入的用户标识
     * @param username 网关注入的用户名
     * @param requestId 可选请求标识
     * @param idempotencyKey 可选幂等键
     * @param request 用户问题
     * @param response Servlet 响应，用于关闭代理缓冲
     * @return 会逐事件刷新到客户端的 SSE 发射器
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestHeader(value = USERNAME_HEADER, required = false) String username,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ChatRequest request,
            HttpServletResponse response) {
        ChatCommand command = buildCommand(userId, username, requestId, idempotencyKey, request);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        // 禁止网关或反向代理转换、压缩和缓冲 SSE，确保每个 DELTA 都能立即抵达浏览器。
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        // SseEmitter.send 会在每个事件写入后刷新 Servlet 响应，不等待整个 Flux 完成。
        Disposable subscription = agentChatService.stream(command)
                .onErrorResume(exception -> Mono.just(agentChatService.toErrorEvent(command, exception)))
                .subscribe(
                        event -> sendEvent(emitter, event, subscriptionRef),
                        emitter::completeWithError,
                        emitter::complete);
        subscriptionRef.set(subscription);

        // 浏览器断开、页面切换或容器超时时必须取消上游，避免模型继续生成无消费者的内容。
        emitter.onCompletion(() -> dispose(subscriptionRef));
        emitter.onTimeout(() -> {
            dispose(subscriptionRef);
            emitter.complete();
        });
        emitter.onError(ignored -> dispose(subscriptionRef));
        return emitter;
    }

    /**
     * 把单个对话事件立即写入 SSE 响应，写入失败时同步取消模型上游。
     *
     * @param emitter 当前 HTTP 连接的 SSE 发射器
     * @param event 待发送的对话事件
     * @param subscriptionRef 当前模型流订阅引用
     */
    private void sendEvent(
            SseEmitter emitter,
            ChatEvent event,
            AtomicReference<Disposable> subscriptionRef) {
        try {
            // 事件名保持与前端协议一致，正文作为 JSON 数据逐条发送并立即刷新。
            emitter.send(SseEmitter.event()
                    .id(event.requestId())
                    .name(event.type().name().toLowerCase(java.util.Locale.ROOT))
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (Exception exception) {
            // 客户端断开后停止消费模型和工具流，异常由连接终止语义处理。
            dispose(subscriptionRef);
            emitter.completeWithError(exception);
        }
    }

    /**
     * 原子取消当前 SSE 对应的 Reactor 订阅。
     *
     * @param subscriptionRef 当前模型流订阅引用
     */
    private void dispose(AtomicReference<Disposable> subscriptionRef) {
        // getAndSet 防止完成、超时和网络错误并发触发重复取消。
        Disposable subscription = subscriptionRef.getAndSet(null);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    /**
     * 取消当前用户指定的流式生成任务。
     *
     * @param userId 网关注入的用户标识
     * @param request 包含会话标识和请求标识的取消请求
     * @return 无内容响应，取消结果由服务端轮次状态保证
     */
    @PostMapping("/chat/cancel")
    public ResponseEntity<Void> cancel(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestBody ChatCancelRequest request) {
        // 服务层同时校验会话归属并取消 Reactor 订阅，不能仅依赖前端断开连接。
        agentChatService.cancel(userId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * 使用认证头和客户端幂等头构造不可变对话命令。
     *
     * @param userId 用户标识
     * @param username 用户名
     * @param requestId 可选请求标识
     * @param idempotencyKey 可选幂等键
     * @param request 请求体
     * @return 完整对话命令
     */
    private ChatCommand buildCommand(
            String userId,
            String username,
            String requestId,
            String idempotencyKey,
            ChatRequest request) {
        if (request == null) {
            throw new AgentChatException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "请求体不能为空");
        }

        // 客户端未提供请求标识时由服务端生成；未单独提供幂等键时沿用该请求标识。
        String actualRequestId = hasText(requestId) ? requestId.trim() : randomRequestId();
        String actualIdempotencyKey = hasText(idempotencyKey)
                ? idempotencyKey.trim() : actualRequestId;
        return new ChatCommand(
                actualRequestId, actualIdempotencyKey, userId, username,
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
     * 生成符合数据库长度约束的请求标识。
     *
     * @return 32 位无分隔符 UUID
     */
    private String randomRequestId() {
        // 去掉连字符后长度固定，能够直接写入请求标识列并作为默认幂等键。
        return UUID.randomUUID().toString().replace("-", "");
    }
}
