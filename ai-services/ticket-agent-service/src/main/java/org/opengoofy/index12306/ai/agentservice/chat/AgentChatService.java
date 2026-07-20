package org.opengoofy.index12306.ai.agentservice.chat;

import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatCancelRequest;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatModels.ChatResult;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentChatProperties;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.model.routing.ModelRoutingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 提供智能体对话的接口级服务，并把单轮业务流程交给独立流水线执行。
 */
@Service
public class AgentChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentChatService.class);
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final int MAX_TITLE_LENGTH = 200;

    private final ConversationMemoryService conversationMemoryService;
    private final AgentChatPipeline chatPipeline;
    private final AgentChatProperties chatProperties;
    private final AgentChatMetrics chatMetrics;
    private final ConcurrentMap<String, Sinks.One<Void>> activeTurnCancels = new ConcurrentHashMap<>();

    /**
     * 创建对话入口服务。
     *
     * @param conversationMemoryService 会话和轮次持久化服务
     * @param chatPipeline 单轮对话业务流水线
     * @param chatProperties 在线对话超时配置
     * @param chatMetrics 在线对话首事件、首个文本增量和总耗时指标
     */
    public AgentChatService(
            ConversationMemoryService conversationMemoryService,
            AgentChatPipeline chatPipeline,
            AgentChatProperties chatProperties,
            AgentChatMetrics chatMetrics) {
        this.conversationMemoryService = conversationMemoryService;
        this.chatPipeline = chatPipeline;
        this.chatProperties = chatProperties;
        this.chatMetrics = chatMetrics;
    }

    /**
     * 为当前用户创建独立会话。
     *
     * @param userId 用户标识
     * @param title 可选会话标题
     * @return 新会话标识
     */
    public String createConversation(String userId, String title) {
        requireText(userId, "用户标识不能为空");
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            throw invalidRequest("会话标题不能超过 200 个字符");
        }

        // 会话只建立用户边界，消息由后续对话轮次写入。
        ConversationEntity conversation = conversationMemoryService.createConversation(
                userId, StringUtils.hasText(title) ? title.trim() : null);
        return conversation.getId();
    }

    /**
     * 执行一轮完整对话并返回元数据、增量正文和完成事件。
     *
     * @param command 包含身份、幂等键和用户问题的对话命令
     * @return 可供 SSE 或普通 JSON 接口消费的事件流
     */
    public Flux<ChatEvent> stream(ChatCommand command) {
        validateCommand(command);

        // 日志和指标都从订阅时刻开始，覆盖路由、模型、工具和最终持久化的完整在线链路。
        return chatMetrics.observe(Flux.defer(() -> {
            long startedNanos = System.nanoTime();
            LOGGER.info("Agent对话开始，requestId={}, conversationId={}",
                    command.requestId(), command.conversationId());
            return streamWithCancellation(command)
                    .doOnNext(event -> {
                        if (event.type() == AgentChatModels.EventType.META) {
                            LOGGER.info("Agent会话上下文加载完成，requestId={}, turnId={}, reused={}",
                                    event.requestId(), event.turnId(), event.reused());
                        } else if (event.type() == AgentChatModels.EventType.DONE) {
                            LOGGER.info("Agent对话完成，requestId={}, turnId={}, contentLength={}, durationMs={}",
                                    event.requestId(), event.turnId(),
                                    event.content() == null ? 0 : event.content().length(),
                                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
                        }
                    })
                    .doOnError(exception -> LOGGER.warn(
                            "Agent对话失败，requestId={}, conversationId={}, exceptionType={}, durationMs={}",
                            command.requestId(), command.conversationId(), exception.getClass().getSimpleName(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)))
                    .doOnCancel(() -> LOGGER.info(
                            "Agent对话订阅已取消，requestId={}, conversationId={}, durationMs={}",
                            command.requestId(), command.conversationId(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)));
        }));
    }

    /**
     * 显式终止指定请求的模型流，并将仍在运行的持久化轮次置为取消状态。
     *
     * @param userId 当前用户标识
     * @param request 需要取消的会话和请求标识
     * @return 是否取消了运行中的轮次或模型流
     */
    public boolean cancel(String userId, ChatCancelRequest request) {
        if (request == null) {
            throw invalidRequest("取消请求不能为空");
        }
        requireText(userId, "用户标识不能为空");
        requireText(request.conversationId(), "会话标识不能为空");
        requireText(request.requestId(), "请求标识不能为空");

        // 先持久化取消状态，保证模型客户端未能及时响应取消信号时轮次也不会永久处于运行中。
        boolean turnCancelled = conversationMemoryService.cancelTurn(
                userId, request.conversationId().trim(), request.requestId().trim());
        Sinks.One<Void> cancellation = activeTurnCancels.get(request.requestId().trim());
        if (cancellation != null) {
            // 该信号会向 Reactor 上游传播取消，从而中断模型流和可取消的工具调用。
            cancellation.tryEmitEmpty();
        }
        boolean cancelled = turnCancelled || cancellation != null;
        LOGGER.info("Agent收到取消请求，requestId={}, conversationId={}, cancelled={}",
                request.requestId().trim(), request.conversationId().trim(), cancelled);
        return cancelled;
    }

    /**
     * 为单次流式订阅绑定取消信号、超时处理和结束清理。
     *
     * @param command 已校验的对话命令
     * @return 可取消的 SSE 事件流
     */
    private Flux<ChatEvent> streamWithCancellation(ChatCommand command) {
        Sinks.One<Void> newCancellation = Sinks.one();
        Sinks.One<Void> registeredCancellation = activeTurnCancels.putIfAbsent(
                command.requestId(), newCancellation);
        boolean registeredByCurrentStream = registeredCancellation == null;
        Sinks.One<Void> cancellation = registeredByCurrentStream
                ? newCancellation : registeredCancellation;

        // 超时和显式取消都会取消上游订阅，已有的 doOnCancel 会同步终止数据库轮次。
        return Flux.defer(() -> chatPipeline.execute(command))
                .takeUntilOther(cancellation.asMono())
                .timeout(chatProperties.responseTimeout())
                .onErrorMap(TimeoutException.class, ignored -> new AgentChatException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "CHAT_TIMEOUT",
                        "智能体响应时间过长，本次生成已停止，请稍后重试"))
                .doFinally(ignored -> {
                    if (registeredByCurrentStream) {
                        activeTurnCancels.remove(command.requestId(), newCancellation);
                    }
                });
    }

    /**
     * 执行一轮对话并只返回最终完整回答。
     *
     * @param command 对话命令
     * @return 最终回答异步结果
     */
    public Mono<ChatResult> chat(ChatCommand command) {
        // 普通 JSON 接口收集同一事件流，同时返回最终回答和可选的操作确认信息。
        return stream(command).collectList().map(events -> {
            ChatEvent done = events.stream()
                    .filter(event -> event.type() == AgentChatModels.EventType.DONE)
                    .findFirst()
                    .orElseThrow(() -> new AgentChatException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "MISSING_DONE_EVENT", "对话未正常完成"));
            ActionConfirmationView action = events.stream()
                    .filter(event -> event.type() == AgentChatModels.EventType.ACTION_REQUIRED)
                    .map(ChatEvent::action)
                    .findFirst()
                    .orElse(null);
            return new ChatResult(
                    done.requestId(), done.conversationId(), done.turnId(),
                    done.content(), done.reused(), action);
        });
    }

    /**
     * 把内部异常转换为不泄露模型、工具或数据库正文的 SSE 错误事件。
     *
     * @param command 原始对话命令
     * @param exception 内部异常
     * @return 安全错误事件
     */
    public ChatEvent toErrorEvent(ChatCommand command, Throwable exception) {
        if (exception instanceof AgentChatException chatException) {
            return ChatEvent.error(command, chatException.failureCategory(), chatException.getMessage());
        }
        if (exception instanceof ModelRoutingException routingException) {
            return ChatEvent.error(
                    command, routingException.failureCategory().name(), "模型服务暂时不可用，请稍后重试");
        }
        return ChatEvent.error(command, "INTERNAL_ERROR", "对话处理失败，请稍后重试");
    }

    /**
     * 校验外部对话命令长度和必填字段。
     *
     * @param command 对话命令
     */
    private void validateCommand(ChatCommand command) {
        if (command == null) {
            throw invalidRequest("请求体不能为空");
        }
        requireText(command.requestId(), "请求标识不能为空");
        requireText(command.idempotencyKey(), "幂等键不能为空");
        requireText(command.userId(), "用户标识不能为空");
        requireText(command.conversationId(), "会话标识不能为空");
        requireText(command.message(), "用户问题不能为空");
        if (command.requestId().length() > 64 || command.idempotencyKey().length() > 128) {
            throw invalidRequest("请求标识或幂等键过长");
        }
        if (command.message().length() > MAX_MESSAGE_LENGTH) {
            throw invalidRequest("用户问题不能超过 4000 个字符");
        }
    }

    /**
     * 校验文本字段并统一转换为客户端参数错误。
     *
     * @param value 字段值
     * @param message 安全错误提示
     */
    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw invalidRequest(message);
        }
    }

    /**
     * 创建统一的无效请求异常。
     *
     * @param message 安全错误提示
     * @return 参数错误异常
     */
    private AgentChatException invalidRequest(String message) {
        return new AgentChatException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

}
