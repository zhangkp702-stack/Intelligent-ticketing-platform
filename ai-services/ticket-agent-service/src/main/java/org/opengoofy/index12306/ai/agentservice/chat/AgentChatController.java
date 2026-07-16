package org.opengoofy.index12306.ai.agentservice.chat;

import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatRequest;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatResult;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ConversationPage;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.CreateConversationRequest;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.CreateConversationResponse;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.HistoryMessagePage;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.RecoverableActionView;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionService;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationHistoryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.UUID;

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
     * @return SSE 对话事件流
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatEvent>> stream(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestHeader(value = USERNAME_HEADER, required = false) String username,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ChatRequest request) {
        ChatCommand command = buildCommand(userId, username, requestId, idempotencyKey, request);

        // SSE 已经开始后不能再修改 HTTP 状态，异常统一转为安全 ERROR 事件。
        return agentChatService.stream(command)
                .onErrorResume(exception -> Flux.just(agentChatService.toErrorEvent(command, exception)))
                .map(event -> ServerSentEvent.<ChatEvent>builder(event)
                        .id(event.requestId())
                        .event(event.type().name().toLowerCase(Locale.ROOT))
                        .build());
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
