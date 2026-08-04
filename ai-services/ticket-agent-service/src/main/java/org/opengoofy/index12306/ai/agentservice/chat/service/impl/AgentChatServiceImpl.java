package org.opengoofy.index12306.ai.agentservice.chat.service.impl;

import org.opengoofy.index12306.ai.agentservice.chat.exception.AgentChatException;
import org.opengoofy.index12306.ai.agentservice.chat.execution.exception.ExecutionLeaseLostException;
import org.opengoofy.index12306.ai.agentservice.chat.execution.AgentChatPipeline;
import org.opengoofy.index12306.ai.agentservice.chat.observability.AgentChatMetrics;
import org.opengoofy.index12306.ai.agentservice.chat.service.AgentChatService;
import org.opengoofy.index12306.ai.agentservice.chat.stream.service.DurableStreamEventService;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels;


import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.PrepareTurnResponse;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.TurnStatusView;
import org.opengoofy.index12306.ai.agentservice.chat.config.AgentChatProperties;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.opengoofy.index12306.ai.agentservice.conversation.exception.TurnSubmissionException;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.exception.ModelRoutingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 提供智能体对话的接口级服务，并把单轮业务流程交给独立流水线执行。
 */
@Service
public class AgentChatServiceImpl implements AgentChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentChatServiceImpl.class);
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int STREAM_REPLAY_BATCH_SIZE = 256;
    private static final Duration STREAM_REPLAY_POLL_INTERVAL = Duration.ofMillis(250);

    private final ConversationMemoryService conversationMemoryService;
    private final AgentChatPipeline chatPipeline;
    private final AgentChatProperties chatProperties;
    private final AgentChatMetrics chatMetrics;
    private final DurableStreamEventService durableStreamEventService;
    private final ConcurrentMap<String, Sinks.One<Void>> activeTurnCancels = new ConcurrentHashMap<>();

    /**
     * 创建对话入口服务。
     *
     * @param conversationMemoryService 会话和轮次持久化服务
     * @param chatPipeline 单轮对话业务流水线
     * @param chatProperties 在线对话超时配置
     * @param chatMetrics 在线对话首事件、首个文本增量和总耗时指标
     * @param durableStreamEventService SSE 事件持久化和重放服务
     */
    public AgentChatServiceImpl(
            ConversationMemoryService conversationMemoryService,
            AgentChatPipeline chatPipeline,
            AgentChatProperties chatProperties,
            AgentChatMetrics chatMetrics,
            DurableStreamEventService durableStreamEventService) {
        this.conversationMemoryService = conversationMemoryService;
        this.chatPipeline = chatPipeline;
        this.chatProperties = chatProperties;
        this.chatMetrics = chatMetrics;
        this.durableStreamEventService = durableStreamEventService;
    }

    /**
     * 为当前用户创建独立会话。
     *
     * @param userId 用户标识
     * @param title 可选会话标题
     * @return 新会话标识
     */
    @Override
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
     * 为当前用户预创建服务端轮次并返回首次提交凭证。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @return 服务端轮次标识、提交令牌和截止时间
     */
    @Override
    public PrepareTurnResponse prepareTurn(String userId, String conversationId) {
        requireText(userId, "用户标识不能为空");
        requireText(conversationId, "会话标识不能为空");

        // 会话所有权和令牌签发由持久化服务在同一事务边界内完成。
        ConversationMemoryService.PreparedTurn prepared = conversationMemoryService.prepareTurn(
                userId, conversationId.trim());
        return new PrepareTurnResponse(
                prepared.turnId(), prepared.submissionToken(), prepared.expiresAt());
    }

    /**
     * 查询当前用户轮次的持久化状态并返回可安全展示的结果。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @return 当前轮次状态和已完成回答
     */
    @Override
    public TurnStatusView getTurn(String userId, String turnId) {
        requireText(userId, "用户标识不能为空");
        requireText(turnId, "轮次标识不能为空");

        // 状态查询始终通过会话所有权校验，不允许只凭 turnId 探测其他用户数据。
        ConversationMemoryService.TurnState state = conversationMemoryService.getTurnState(
                userId, turnId.trim());
        return new TurnStatusView(
                state.turnId(),
                state.conversationId(),
                state.status(),
                state.assistantContent(),
                state.failureCategory(),
                state.startedAt(),
                state.finishedAt());
    }

    /**
     * 执行一轮完整对话并返回元数据、增量正文和完成事件。
     *
     * @param command 包含身份、幂等键和用户问题的对话命令
     * @return 可供 SSE 接口消费的事件流
     */
    @Override
    public Flux<ChatEvent> stream(ChatCommand command) {
        validateCommand(command);

        // 指标仍观察真实流水线，发送层只会看到已经成功写入事件日志的事件。
        Flux<ChatEvent> observedStream = chatMetrics.observe(
                Flux.defer(() -> createLoggedStream(command)));
        Flux<ChatEvent> safeBusinessStream = observedStream
                // 只转换业务流水线异常；事件日志写入失败必须保留为传输失败并等待重连补偿。
                .onErrorResume(exception -> Mono.just(toErrorEvent(command, exception)));
        return safeBusinessStream
                // concatMap 保证数据库事件序号与上游事件顺序完全一致。
                .concatMap(event -> persistEvent(command.userId(), event))
                // 显式取消等“无错误完成”场景根据数据库 Turn 状态补齐终态事件。
                .concatWith(Mono.defer(() -> Mono.justOrEmpty(
                        durableStreamEventService.ensureTerminal(
                                command.userId(), command.turnId()))));
    }

    /**
     * 从 Last-Event-ID 后继续轮询持久化事件，不再次调用模型或业务工具。
     *
     * @param command 原轮次及本次网络尝试信息
     * @param lastEventSequence 客户端最后收到的事件序号
     * @return 从下一事件开始直到 DONE 或 ERROR 的重放流
     */
    @Override
    public Flux<ChatEvent> resume(ChatCommand command, long lastEventSequence) {
        validateCommand(command);
        if (lastEventSequence < 0L) {
            throw invalidRequest("Last-Event-ID 不能小于零");
        }

        return Flux.defer(() -> {
            // 首次请求可能在抵达服务端前断网；DRAFT 轮次必须在重连时真正启动一次。
            TurnStatusView state = getTurn(command.userId(), command.turnId());
            if (!state.conversationId().equals(command.conversationId())) {
                throw invalidRequest("轮次不属于当前会话");
            }
            if (state.status() == org.opengoofy.index12306.ai.agentservice.conversation.enums.TurnStatus.DRAFT) {
                return stream(command);
            }
            return replayPersistedEvents(command.userId(), command.turnId(), lastEventSequence);
        });
    }

    /**
     * 周期读取其他实例持续写入的事件，直到观察到持久化终态。
     *
     * @param userId 当前用户标识
     * @param turnId 服务端轮次标识
     * @param lastEventSequence 初始客户端游标
     * @return 跨实例可消费的有序事件流
     */
    private Flux<ChatEvent> replayPersistedEvents(
            String userId,
            String turnId,
            long lastEventSequence) {
        AtomicLong cursor = new AtomicLong(lastEventSequence);

        // 数据库轮询不占用 Reactor 事件线程；concatMap 防止慢查询产生并发重叠游标。
        return Flux.interval(Duration.ZERO, STREAM_REPLAY_POLL_INTERVAL)
                .concatMap(ignored -> Mono.fromCallable(() -> durableStreamEventService.poll(
                                userId, turnId, cursor.get(), STREAM_REPLAY_BATCH_SIZE))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMapIterable(events -> events)
                .doOnNext(event -> cursor.accumulateAndGet(event.eventSequence(), Math::max))
                .takeUntil(this::isTerminalEvent);
    }

    /**
     * 将单个上游事件持久化并转换为 Reactor 单值信号。
     *
     * @param userId 当前用户标识
     * @param event 待发布事件
     * @return 新写入的事件；终态后的重复事件为空信号
     */
    private Mono<ChatEvent> persistEvent(String userId, ChatEvent event) {
        // defer 确保持久化发生在订阅阶段，并让事务异常沿响应式链传播。
        return Mono.defer(() -> Mono.justOrEmpty(
                durableStreamEventService.append(userId, event)));
    }

    /**
     * 判断持久化事件是否已经结束本次传输。
     *
     * @param event 当前事件
     * @return DONE 或 ERROR 时返回 true
     */
    private boolean isTerminalEvent(ChatEvent event) {
        return event.type() == AgentChatModels.EventType.DONE
                || event.type() == AgentChatModels.EventType.ERROR;
    }

    /**
     * 为一次实际订阅创建对话事件流，并绑定开始、进度、失败和取消日志。
     *
     * @param command 对话命令
     * @return 带生命周期日志的对话事件流
     */
    private Flux<ChatEvent> createLoggedStream(ChatCommand command) {
        long startedNanos = System.nanoTime();

        // 开始时间必须在订阅时采集，避免把事件流创建到真正消费之间的等待计入在线耗时。
        LOGGER.info("Agent对话开始，requestId={}, conversationId={}",
                command.requestId(), command.conversationId());
        // 创建包含任务规划、固定业务链和最终回答生成的完整对话流。
        Flux<ChatEvent> eventStream = streamWithCancellation(command);

        // 生命周期回调只记录观测信息，不改变事件、异常或取消信号。
        Flux<ChatEvent> loggedStream = eventStream
                .doOnNext(event -> logProgress(event, startedNanos))
                .doOnError(exception -> logFailure(command, exception, startedNanos))
                .doOnCancel(() -> logCancellation(command, startedNanos));
        return loggedStream;
    }

    /**
     * 为单次流式订阅绑定取消信号、超时处理和结束清理。
     *
     * @param command 已校验的对话命令
     * @return 可取消的 SSE 事件流
     */
    private Flux<ChatEvent> streamWithCancellation(ChatCommand command) {
        // 每次订阅先创建独立的一次性取消信号；tryEmitEmpty 只表示“停止”，不携带业务数据。
        Sinks.One<Void> newCancellation = Sinks.one();

        // 以 requestId 原子注册取消信号：首次请求保存新信号；重复订阅保留已运行请求的原信号。
        Sinks.One<Void> registeredCancellation = activeTurnCancels.putIfAbsent(
                command.requestId(), newCancellation);
        boolean registeredByCurrentStream = registeredCancellation == null;

        // 后续流始终监听实际登记的信号，保证同一 requestId 的显式取消能通知全部相关订阅。
        Sinks.One<Void> cancellation = registeredByCurrentStream
                ? newCancellation : registeredCancellation;

        // defer 使 Pipeline 在订阅时才执行；此时取消、超时和结束清理规则已经完成组装。
        Flux<ChatEvent> pipelineStream = Flux.defer(() -> chatPipeline.execute(command));

        // 取消信号完成时停止模型和业务上游；timeout 限制两次上游信号之间的最长等待时间。
        Flux<ChatEvent> guardedStream = pipelineStream
                // 收到取消信号自动停止
                .takeUntilOther(cancellation.asMono())
                // 长时间没有接收数据自动超时
                .timeout(chatProperties.responseTimeout())
                // 将 Reactor 技术超时转换为可安全发送给 SSE 客户端的业务异常。
                .onErrorMap(TimeoutException.class, this::toChatTimeout)
                // 正常完成、异常、显式取消和客户端断开均会进入此处，防止取消信号残留在进程内存中。
                .doFinally(ignored -> removeCancellationRegistration(
                        command, newCancellation, registeredByCurrentStream));
        return guardedStream;
    }

    /**
     * 记录上下文就绪和最终回答完成事件。
     *
     * @param event 当前对话事件
     * @param startedNanos 本次订阅开始时间
     */
    private void logProgress(ChatEvent event, long startedNanos) {
        if (event.type() == AgentChatModels.EventType.META) {
            // META 表示会话上下文已经加载，可以记录轮次和幂等复用信息。
            LOGGER.info("Agent会话上下文加载完成，requestId={}, turnId={}, reused={}",
                    event.requestId(), event.turnId(), event.reused());
        } else if (event.type() == AgentChatModels.EventType.DONE) {
            // DONE 是完整在线链路的成功终点，同时记录回答长度与总耗时。
            LOGGER.info("Agent对话完成，requestId={}, turnId={}, contentLength={}, durationMs={}",
                    event.requestId(), event.turnId(),
                    event.content() == null ? 0 : event.content().length(),
                    elapsedMillis(startedNanos));
        }
    }

    /**
     * 记录对话事件流异常终止信息。
     *
     * @param command 对话命令
     * @param exception 终止事件流的异常
     * @param startedNanos 本次订阅开始时间
     */
    private void logFailure(ChatCommand command, Throwable exception, long startedNanos) {
        // 日志只记录稳定标识和异常类型，不输出用户问题或模型响应正文。
        LOGGER.warn("Agent对话失败，requestId={}, conversationId={}, exceptionType={}, durationMs={}",
                command.requestId(), command.conversationId(), exception.getClass().getSimpleName(),
                elapsedMillis(startedNanos));
    }

    /**
     * 记录客户端取消对话事件流订阅的信息。
     *
     * @param command 对话命令
     * @param startedNanos 本次订阅开始时间
     */
    private void logCancellation(ChatCommand command, long startedNanos) {
        // 取消与异常分开记录，便于区分客户端断开和服务端处理失败。
        LOGGER.info("Agent对话订阅已取消，requestId={}, conversationId={}, durationMs={}",
                command.requestId(), command.conversationId(), elapsedMillis(startedNanos));
    }

    /**
     * 计算当前时刻距离订阅开始的毫秒数。
     *
     * @param startedNanos 本次订阅开始时间
     * @return 已经过的毫秒数
     */
    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /**
     * 显式终止指定请求的模型流，并将仍在运行的持久化轮次置为取消状态。
     *
     * @param userId 当前用户标识
     * @param turnId 服务端轮次标识
     * @return 是否取消了运行中的轮次或模型流
     */
    @Override
    public boolean cancel(String userId, String turnId) {
        requireText(userId, "用户标识不能为空");
        requireText(turnId, "轮次标识不能为空");
        String normalizedTurnId = turnId.trim();

        // 先持久化取消状态，保证模型客户端未能及时响应取消信号时轮次也不会永久处于运行中。
        boolean turnCancelled = conversationMemoryService.cancelTurn(userId, normalizedTurnId);
        Sinks.One<Void> cancellation = activeTurnCancels.get(normalizedTurnId);
        if (cancellation != null) {
            // 该信号会向 Reactor 上游传播取消，从而中断模型流和可取消的工具调用。
            cancellation.tryEmitEmpty();
        }
        boolean cancelled = turnCancelled || cancellation != null;
        LOGGER.info("Agent收到取消请求，turnId={}, cancelled={}", normalizedTurnId, cancelled);
        return cancelled;
    }

    /**
     * 将底层响应超时转换为稳定的对话接口异常。
     *
     * @param ignored Reactor 产生的原始超时异常
     * @return 可安全返回给调用方的对话超时异常
     */
    private AgentChatException toChatTimeout(TimeoutException ignored) {
        // 对外隐藏模型路由和供应商细节，只暴露稳定错误码与重试提示。
        return new AgentChatException(
                HttpStatus.GATEWAY_TIMEOUT,
                "CHAT_TIMEOUT",
                "智能体响应时间过长，本次生成已停止，请稍后重试");
    }

    /**
     * 在当前流结束后移除由本次订阅创建的取消信号。
     *
     * @param command 当前对话命令
     * @param newCancellation 本次尝试创建的取消信号
     * @param registeredByCurrentStream 是否由本次订阅完成注册
     */
    private void removeCancellationRegistration(
            ChatCommand command,
            Sinks.One<Void> newCancellation,
            boolean registeredByCurrentStream) {
        // 复用其他订阅的取消信号时不能由当前流删除其注册关系。
        if (registeredByCurrentStream) {
            activeTurnCancels.remove(command.requestId(), newCancellation);
        }
    }

    /**
     * 把内部异常转换为不泄露模型、工具或数据库正文的 SSE 错误事件。
     *
     * @param command 原始对话命令
     * @param exception 内部异常
     * @return 安全错误事件
     */
    @Override
    public ChatEvent toErrorEvent(ChatCommand command, Throwable exception) {
        if (exception instanceof AgentChatException chatException) {
            return ChatEvent.error(command, chatException.failureCategory(), chatException.getMessage());
        }
        if (exception instanceof TurnSubmissionException submissionException) {
            // 轮次协议错误使用稳定分类和安全提示，不返回令牌或原问题内容。
            return switch (submissionException.reason()) {
                case INVALID_TOKEN -> ChatEvent.error(
                        command, "TURN_SUBMISSION_TOKEN_INVALID", "轮次提交凭证无效，请重新创建轮次");
                case SUBMISSION_EXPIRED -> ChatEvent.error(
                        command, "TURN_SUBMISSION_EXPIRED", "轮次提交凭证已过期，请重新创建轮次");
                case PAYLOAD_MISMATCH -> ChatEvent.error(
                        command, "TURN_PAYLOAD_MISMATCH", "该轮次已经绑定其他问题，不能修改后重试");
            };
        }
        if (exception instanceof ModelRoutingException routingException) {
            return ChatEvent.error(
                    command, routingException.failureCategory().name(), "模型服务暂时不可用，请稍后重试");
        }
        if (exception instanceof ExecutionLeaseLostException) {
            // 跨实例取消或租约接管只返回稳定分类，不暴露执行者和 fencing token。
            return ChatEvent.error(
                    command, "TURN_EXECUTION_LEASE_LOST", "当前处理已被取消或转移，请查询轮次状态");
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
        requireText(command.turnId(), "轮次标识不能为空");
        requireText(command.attemptId(), "网络尝试标识不能为空");
        requireText(command.submissionToken(), "轮次提交令牌不能为空");
        requireText(command.userId(), "用户标识不能为空");
        requireText(command.conversationId(), "会话标识不能为空");
        requireText(command.message(), "用户问题不能为空");
        if (command.turnId().length() > 64 || command.attemptId().length() > 64) {
            throw invalidRequest("轮次标识或网络尝试标识过长");
        }
        if (command.submissionToken().length() > 256) {
            throw invalidRequest("轮次提交令牌过长");
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
