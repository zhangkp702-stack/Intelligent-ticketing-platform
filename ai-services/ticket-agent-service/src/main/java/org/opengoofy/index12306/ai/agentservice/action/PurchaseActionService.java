package org.opengoofy.index12306.ai.agentservice.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.action.ActionStateStore.ClaimedAction;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ActionStatusView;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ConfirmPurchaseCommand;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchaseDraftResult;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchaseExecutionResult;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchasePassenger;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchasePayload;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.RecoverableActionView;
import org.opengoofy.index12306.ai.agentservice.action.config.AgentActionProperties;
import org.opengoofy.index12306.ai.agentservice.action.domain.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.domain.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.action.domain.AgentActionType;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatException;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 创建购票草案，并统一签发和执行购票、取消及退票的显式确认操作。
 */
@Service
public class PurchaseActionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PurchaseActionService.class);
    private static final String PURCHASE_REJECTED_MARKER = "PURCHASE_REJECTED:";
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SEAT_PATTERN = Pattern.compile("[0-9]{1,2}[A-Z]{1,2}");
    private static final int MAX_PASSENGERS = 5;

    private final ActionStateStore stateStore;
    private final ConfirmationTokenService tokenService;
    private final ObjectProvider<ConfirmedPurchaseExecutor> executorProvider;
    private final ObjectProvider<ConfirmedTicketOperationExecutor> ticketOperationExecutorProvider;
    private final TicketOperationActionService ticketOperationActionService;
    private final AgentActionProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 创建受确认保护的购票操作服务。
     *
     * @param stateStore 操作状态事务服务
     * @param tokenService 确认令牌服务
     * @param executorProvider 专用 MCP 写执行器
     * @param ticketOperationExecutorProvider 取消和退票专用 MCP 写执行器
     * @param ticketOperationActionService 取消和退票草案服务
     * @param properties 操作确认配置
     * @param objectMapper JSON 序列化器
     * @param clock 统一时钟
     */
    public PurchaseActionService(
            ActionStateStore stateStore,
            ConfirmationTokenService tokenService,
            ObjectProvider<ConfirmedPurchaseExecutor> executorProvider,
            ObjectProvider<ConfirmedTicketOperationExecutor> ticketOperationExecutorProvider,
            TicketOperationActionService ticketOperationActionService,
            AgentActionProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.stateStore = stateStore;
        this.tokenService = tokenService;
        this.executorProvider = executorProvider;
        this.ticketOperationExecutorProvider = ticketOperationExecutorProvider;
        this.ticketOperationActionService = ticketOperationActionService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 规范化购票参数并在当前轮次创建不可执行草案。
     *
     * @param context 已验证的对话上下文
     * @param requestedPayload 模型生成的购票草案参数
     * @return 不包含确认令牌的安全草案结果
     */
    public PurchaseDraftResult prepare(
            AgentRequestContext context,
            PurchasePayload requestedPayload) {
        PurchasePayload payload = normalizeAndValidate(requestedPayload);
        String payloadJson = writeJson(payload);
        String payloadHash = fingerprint(payloadJson);

        // 草案只写入 Agent 独立数据库，不会调用票务服务创建订单。
        ActionDraftEntity action = stateStore.createPurchaseDraft(
                context,
                payloadJson,
                payloadHash,
                clock.instant().plus(properties.confirmationTtl()));
        return new PurchaseDraftResult(
                action.getId(), action.getStatus(), summary(payload), action.getConfirmationExpiresAt());
    }

    /**
     * 返回轮次中等待确认的高风险操作草案及服务端签发令牌。
     *
     * @param userId 当前用户标识
     * @param turnId 轮次标识
     * @return 待确认操作；不存在或已经终止时为空
     */
    public Optional<ActionConfirmationView> confirmationForTurn(String userId, String turnId) {
        ActionDraftEntity action = stateStore.findByTurn(userId, turnId).orElse(null);
        if (action == null || action.getStatus() != AgentActionStatus.AWAITING_CONFIRMATION) {
            return Optional.empty();
        }

        // 确认令牌只进入服务端结构化事件，不作为模型工具结果返回。
        String actionSummary = action.getActionType() == AgentActionType.TICKET_PURCHASE
                ? summary(readPayload(action.getPayloadJson()))
                : ticketOperationActionService.summary(action);
        return Optional.of(new ActionConfirmationView(
                action.getId(), action.getActionType().name(), action.getStatus(), actionSummary,
                action.getConfirmationExpiresAt(), tokenService.issue(action)));
    }

    /**
     * 原子消费用户确认并通过对应专用 MCP 写执行器完成一次真实操作。
     *
     * @param command 包含身份、幂等键和确认令牌的命令
     * @return 最新操作状态和脱敏业务结果
     */
    public ActionStatusView confirm(ConfirmPurchaseCommand command) {
        validateConfirmationCommand(command);
        ActionDraftEntity current = stateStore.get(command.userId(), command.actionId());
        if (current.getStatus() == AgentActionStatus.SUCCEEDED) {
            // 确认响应丢失后的客户端重试直接返回已保存结果，不重复下单。
            return toStatusView(current);
        }
        if (current.getStatus() != AgentActionStatus.AWAITING_CONFIRMATION) {
            throw conflict("ACTION_NOT_CONFIRMABLE", "操作已经确认、终止或正在执行");
        }
        ConfirmedPurchaseExecutor purchaseExecutor = null;
        ConfirmedTicketOperationExecutor ticketOperationExecutor = null;
        if (current.getActionType() == AgentActionType.TICKET_PURCHASE) {
            // 购票继续使用只发现真实下单工具的隔离执行器。
            purchaseExecutor = executorProvider.getIfAvailable();
            if (purchaseExecutor == null) {
                throw writeMcpUnavailable("购票执行服务暂时不可用，请稍后重新生成草案");
            }
        } else {
            // 取消和退票在消费令牌前重新预览，避免执行用户未确认的新状态或新金额。
            AgentRequestContext context = new AgentRequestContext(
                    command.requestId(), command.userId(), command.username(),
                    current.getConversationId(), current.getTurnId());
            ticketOperationActionService.revalidate(current, context);
            ticketOperationExecutor = ticketOperationExecutorProvider.getIfAvailable();
            if (ticketOperationExecutor == null) {
                throw writeMcpUnavailable("订单操作执行服务暂时不可用，请重新生成草案");
            }
        }

        // 短事务先消费确认并创建执行记录，提交后才进行可能耗时的 MCP 网络调用。
        ClaimedAction claimed;
        try {
            claimed = stateStore.claim(
                    command.userId(), command.actionId(), command.confirmationToken(),
                    command.requestId(), command.idempotencyKey());
        } catch (SecurityException ex) {
            throw new AgentChatException(HttpStatus.FORBIDDEN, "INVALID_CONFIRMATION", "操作确认令牌无效");
        } catch (IllegalStateException ex) {
            throw conflict("ACTION_NOT_CONFIRMABLE", "操作已经确认、过期或幂等键已被使用");
        }

        try {
            // 专用执行器不会注册到回答模型，只能接收已经领取执行权的数据库快照。
            String safeResultJson = claimed.actionType() == AgentActionType.TICKET_PURCHASE
                    ? purchaseExecutor.execute(claimed, command.username())
                    : ticketOperationExecutor.execute(claimed, command.username());
            Object result;
            String orderSn;
            if (claimed.actionType() == AgentActionType.TICKET_PURCHASE) {
                PurchaseExecutionResult purchaseResult = readResult(safeResultJson);
                result = purchaseResult;
                orderSn = purchaseResult.orderSn();
            } else {
                result = ticketOperationActionService.readResult(claimed.actionType(), safeResultJson);
                orderSn = ticketOperationActionService.resultReference(claimed.actionType(), result);
            }
            if (!StringUtils.hasText(orderSn)) {
                throw new IllegalStateException("操作结果缺少订单号");
            }
            stateStore.succeed(
                    claimed.actionId(), safeResultJson, orderSn, fingerprint(safeResultJson));
            return new ActionStatusView(
                    claimed.actionId(), claimed.actionType().name(),
                    AgentActionStatus.SUCCEEDED, orderSn, result, null);
        } catch (RuntimeException ex) {
            if (claimed.actionType() == AgentActionType.TICKET_PURCHASE
                    && isDefinitePurchaseFailure(ex)) {
                // MCP 明确返回工具拒绝时订单没有成功，记录 FAILED 允许用户修正后创建新草案。
                String failureCategory = purchaseFailureCategory(ex);
                stateStore.fail(claimed.actionId(), failureCategory, ex.getClass().getName());
                LOGGER.warn(
                        "Agent购票确认明确失败，requestId={}, actionId={}, failureCategory={}, exceptionType={}",
                        command.requestId(), claimed.actionId(), failureCategory, ex.getClass().getName());
                throw new AgentChatException(
                        HttpStatus.BAD_REQUEST,
                        failureCategory,
                        "购票未成功，当前没有生成可确认的订单，请核对席别、乘车人和余票后重新生成购票草案");
            }

            // 超时、连接中断或无法解析成功响应时仍可能已经创建订单，必须保持 UNKNOWN。
            String unknownCategory = claimed.actionType() == AgentActionType.TICKET_PURCHASE
                    ? "PURCHASE_RESULT_UNKNOWN"
                    : ticketOperationActionService.unknownCategory(claimed.actionType());
            stateStore.markUnknown(
                    claimed.actionId(), unknownCategory, ex.getClass().getName());
            LOGGER.warn(
                    "Agent写操作结果待核对，requestId={}, actionId={}, failureCategory={}, exceptionType={}",
                    command.requestId(), claimed.actionId(), unknownCategory, ex.getClass().getName());
            throw new AgentChatException(
                    HttpStatus.BAD_GATEWAY,
                    unknownCategory,
                    "操作结果暂时无法确认，请先查询本人订单和支付状态，切勿重复提交");
        }
    }

    /**
     * 查询当前用户高风险操作的持久化状态和脱敏结果。
     *
     * @param userId 当前用户标识
     * @param actionId 草案标识
     * @return 操作状态视图
     */
    public ActionStatusView getStatus(String userId, String actionId) {
        return toStatusView(stateStore.get(userId, actionId));
    }

    /**
     * 恢复当前用户会话最近的操作卡片，并只为仍待确认的草案重新签发令牌。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 可恢复操作；会话没有操作时为空
     */
    public Optional<RecoverableActionView> recoverLatestAction(
            String userId,
            String conversationId) {
        ActionDraftEntity action = stateStore
                .findLatestByConversation(userId, conversationId)
                .orElse(null);
        if (action == null) {
            return Optional.empty();
        }

        // 只有服务端仍判定为待确认的草案才重新签发令牌，终态结果不能获得新确认机会。
        String actionSummary = action.getActionType() == AgentActionType.TICKET_PURCHASE
                ? summary(readPayload(action.getPayloadJson()))
                : ticketOperationActionService.summary(action);
        String confirmationToken = action.getStatus() == AgentActionStatus.AWAITING_CONFIRMATION
                ? tokenService.issue(action)
                : null;
        ActionConfirmationView confirmation = new ActionConfirmationView(
                action.getId(),
                action.getActionType().name(),
                action.getStatus(),
                actionSummary,
                action.getConfirmationExpiresAt(),
                confirmationToken);
        return Optional.of(new RecoverableActionView(
                action.getTurnId(), confirmation, toStatusView(action)));
    }

    /**
     * 把草案持久化结果转换为 API 状态视图。
     *
     * @param action 操作草案
     * @return 安全状态视图
     */
    private ActionStatusView toStatusView(ActionDraftEntity action) {
        Object result = null;
        if (StringUtils.hasText(action.getResultJson())) {
            // 按草案类型恢复稳定结果，状态接口不会返回原始 MCP 信封。
            result = action.getActionType() == AgentActionType.TICKET_PURCHASE
                    ? readResult(action.getResultJson())
                    : ticketOperationActionService.readResult(
                            action.getActionType(), action.getResultJson());
        }
        return new ActionStatusView(
                action.getId(), action.getActionType().name(),
                action.getStatus(), action.getResultReference(),
                result, action.getFailureCategory());
    }

    /**
     * 创建写 MCP 服务不可用异常。
     *
     * @param message 用户提示
     * @return HTTP 503 异常
     */
    private AgentChatException writeMcpUnavailable(String message) {
        return new AgentChatException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "WRITE_MCP_UNAVAILABLE",
                message);
    }

    /**
     * 判断购票异常是否已经由 MCP 明确返回为工具拒绝，并排除网络结果不确定场景。
     *
     * @param exception 购票执行异常
     * @return 明确未成功时返回 true
     */
    private boolean isDefinitePurchaseFailure(RuntimeException exception) {
        // 超时或连接中断可能发生在票务服务已经创建订单之后，不能归入明确失败。
        if (containsUncertainTransportFailure(exception)) {
            return false;
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof IllegalArgumentException || current instanceof SecurityException) {
                return true;
            }
            if (current.getMessage() != null
                    && current.getMessage().contains(PURCHASE_REJECTED_MARKER)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 检查异常链和安全摘要中是否包含超时、连接重置等无法确认下单结果的信号。
     *
     * @param exception 购票执行异常
     * @return 结果可能不确定时返回 true
     */
    private boolean containsUncertainTransportFailure(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            String type = current.getClass().getSimpleName().toLowerCase();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (type.contains("timeout")
                    || message.contains("timeout")
                    || message.contains("timed out")
                    || message.contains("connection reset")
                    || message.contains("broken pipe")
                    || message.contains("premature close")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 根据明确拒绝的异常类型生成稳定失败分类，前端据此提示用户修正输入。
     *
     * @param exception 购票执行异常
     * @return 稳定失败分类
     */
    private String purchaseFailureCategory(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SecurityException) {
                return "PURCHASE_FORBIDDEN";
            }
            if (current instanceof IllegalArgumentException) {
                return "INVALID_PURCHASE_REQUEST";
            }
            current = current.getCause();
        }
        return "PURCHASE_REJECTED";
    }

    /**
     * 规范化并校验模型提供的购票草案参数。
     *
     * @param requestedPayload 原始草案参数
     * @return 规范化后的不可变参数
     */
    private PurchasePayload normalizeAndValidate(PurchasePayload requestedPayload) {
        if (requestedPayload == null) {
            throw new IllegalArgumentException("购票参数不能为空");
        }
        String trainId = requiredText(requestedPayload.trainId(), "车次标识", 64);
        if (!IDENTIFIER_PATTERN.matcher(trainId).matches()) {
            throw new IllegalArgumentException("车次标识格式不正确");
        }
        String departure = requiredText(requestedPayload.departure(), "出发站", 64);
        String arrival = requiredText(requestedPayload.arrival(), "到达站", 64);
        if (departure.equals(arrival)) {
            throw new IllegalArgumentException("出发站和到达站不能相同");
        }
        String departureDate = normalizeDepartureDate(requestedPayload.departureDate());
        List<PurchasePassenger> passengers = requestedPayload.passengers() == null
                ? List.of() : requestedPayload.passengers();
        if (passengers.isEmpty() || passengers.size() > MAX_PASSENGERS) {
            throw new IllegalArgumentException("乘车人数量必须在 1 到 5 之间");
        }

        // 乘车人必须唯一，席别使用票务服务已定义的 0 到 14 编码。
        Set<String> passengerIds = new HashSet<>();
        List<PurchasePassenger> normalizedPassengers = passengers.stream().map(passenger -> {
            if (passenger == null) {
                throw new IllegalArgumentException("乘车人参数不能为空");
            }
            String passengerId = requiredText(passenger.passengerId(), "乘车人标识", 64);
            if (!IDENTIFIER_PATTERN.matcher(passengerId).matches() || !passengerIds.add(passengerId)) {
                throw new IllegalArgumentException("乘车人标识格式不正确或重复");
            }
            // 通过集中映射验证编码确实对应公开席别，而不是只校验数值范围。
            PurchaseSeatClass seatClass = PurchaseSeatClass.fromCode(passenger.seatType());
            return new PurchasePassenger(passengerId, seatClass.code());
        }).toList();
        List<String> chooseSeats = requestedPayload.chooseSeats() == null
                ? List.of() : requestedPayload.chooseSeats().stream()
                .map(seat -> requiredText(seat, "座位偏好", 8).toUpperCase())
                .toList();
        if (chooseSeats.size() > normalizedPassengers.size()
                || chooseSeats.stream().anyMatch(seat -> !SEAT_PATTERN.matcher(seat).matches())) {
            throw new IllegalArgumentException("座位偏好格式或数量不正确");
        }
        return new PurchasePayload(
                trainId, departure, arrival, departureDate, normalizedPassengers, chooseSeats);
    }

    /**
     * 规范化乘车日期，并拒绝格式错误或已经过去的日期。
     *
     * @param value 模型提供的乘车日期
     * @return yyyy-MM-dd 格式的乘车日期
     */
    private String normalizeDepartureDate(String value) {
        // 先做文本长度校验，再用严格 ISO 日期解析保证草案和下游指纹稳定一致。
        String normalized = requiredText(value, "乘车日期", 10);
        try {
            LocalDate departureDate = LocalDate.parse(normalized);
            if (departureDate.isBefore(LocalDate.now(clock))) {
                throw new IllegalArgumentException("乘车日期不能早于当前日期");
            }
            return departureDate.toString();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("乘车日期必须使用 yyyy-MM-dd 格式", ex);
        }
    }

    /**
     * 校验确认命令必填字段与数据库长度约束。
     *
     * @param command 确认命令
     */
    private void validateConfirmationCommand(ConfirmPurchaseCommand command) {
        if (command == null
                || !StringUtils.hasText(command.requestId())
                || !StringUtils.hasText(command.idempotencyKey())
                || !StringUtils.hasText(command.userId())
                || !StringUtils.hasText(command.actionId())
                || !StringUtils.hasText(command.confirmationToken())) {
            throw new AgentChatException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "确认参数不完整");
        }
        if (command.requestId().length() > 64 || command.idempotencyKey().length() > 128) {
            throw new AgentChatException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求标识或幂等键过长");
        }
    }

    /**
     * 校验文本并返回去除两端空白的值。
     *
     * @param value 原始文本
     * @param field 字段说明
     * @param maxLength 最大长度
     * @return 规范文本
     */
    private String requiredText(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value) || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + "不能为空或过长");
        }
        return value.trim();
    }

    /**
     * 生成人工确认时展示的购票摘要。
     *
     * @param payload 规范化购票参数
     * @return 不包含证件信息的摘要
     */
    private String summary(PurchasePayload payload) {
        // 摘要明确列出会产生订单的关键字段，用户可在确认前发现车次或人数错误。
        String seatTypes = payload.passengers().stream()
                .map(passenger -> {
                    PurchaseSeatClass seatClass = PurchaseSeatClass.fromCode(passenger.seatType());
                    return seatClass.label() + "（编码 " + seatClass.code() + "）";
                })
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return "购买车次 " + payload.trainId() + "，乘车日期 " + payload.departureDate()
                + "，" + payload.departure() + "→" + payload.arrival()
                + "，乘车人 " + payload.passengers().size() + " 名，席别 " + seatTypes;
    }

    /**
     * 序列化规范购票参数。
     *
     * @param payload 购票参数
     * @return JSON 文本
     */
    private String writeJson(PurchasePayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化购票草案", ex);
        }
    }

    /**
     * 反序列化持久化购票参数。
     *
     * @param json 参数 JSON
     * @return 购票参数
     */
    private PurchasePayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, PurchasePayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("购票草案参数损坏", ex);
        }
    }

    /**
     * 反序列化 MCP 返回的脱敏购票结果。
     *
     * @param json 结果 JSON
     * @return 脱敏购票结果
     */
    private PurchaseExecutionResult readResult(String json) {
        try {
            return objectMapper.readValue(json, PurchaseExecutionResult.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("购票执行结果格式无效", ex);
        }
    }

    /**
     * 计算 JSON 文本 SHA-256 指纹。
     *
     * @param value JSON 文本
     * @return 十六进制指纹
     */
    private String fingerprint(String value) {
        try {
            // 参数与结果指纹支持审计关联，但不能从数据库恢复原始内容。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少 SHA-256", ex);
        }
    }

    /**
     * 创建操作状态冲突异常。
     *
     * @param category 稳定失败分类
     * @param message 用户提示
     * @return HTTP 409 异常
     */
    private AgentChatException conflict(String category, String message) {
        return new AgentChatException(HttpStatus.CONFLICT, category, message);
    }
}
