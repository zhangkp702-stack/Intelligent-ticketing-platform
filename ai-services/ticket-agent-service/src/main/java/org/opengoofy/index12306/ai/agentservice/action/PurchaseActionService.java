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
import org.opengoofy.index12306.ai.agentservice.action.config.AgentActionProperties;
import org.opengoofy.index12306.ai.agentservice.action.domain.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.domain.AgentActionStatus;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 创建购票草案、签发确认视图并在显式确认后执行一次真实购票。
 */
@Service
public class PurchaseActionService {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SEAT_PATTERN = Pattern.compile("[0-9]{1,2}[A-Z]{1,2}");
    private static final int MAX_PASSENGERS = 5;

    private final ActionStateStore stateStore;
    private final ConfirmationTokenService tokenService;
    private final ObjectProvider<ConfirmedPurchaseExecutor> executorProvider;
    private final AgentActionProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 创建受确认保护的购票操作服务。
     *
     * @param stateStore 操作状态事务服务
     * @param tokenService 确认令牌服务
     * @param executorProvider 专用 MCP 写执行器
     * @param properties 操作确认配置
     * @param objectMapper JSON 序列化器
     * @param clock 统一时钟
     */
    public PurchaseActionService(
            ActionStateStore stateStore,
            ConfirmationTokenService tokenService,
            ObjectProvider<ConfirmedPurchaseExecutor> executorProvider,
            AgentActionProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.stateStore = stateStore;
        this.tokenService = tokenService;
        this.executorProvider = executorProvider;
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
     * 返回轮次中等待确认的购票草案及服务端签发令牌。
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
        PurchasePayload payload = readPayload(action.getPayloadJson());
        return Optional.of(new ActionConfirmationView(
                action.getId(), action.getActionType().name(), action.getStatus(), summary(payload),
                action.getConfirmationExpiresAt(), tokenService.issue(action)));
    }

    /**
     * 原子消费用户确认并通过专用 MCP 写执行器创建真实订单。
     *
     * @param command 包含身份、幂等键和确认令牌的命令
     * @return 最新操作状态和脱敏购票结果
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
        ConfirmedPurchaseExecutor executor = executorProvider.getIfAvailable();
        if (executor == null) {
            throw new AgentChatException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "WRITE_MCP_UNAVAILABLE",
                    "购票执行服务暂时不可用，请稍后重新生成草案");
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
            String safeResultJson = executor.execute(claimed, command.username());
            PurchaseExecutionResult result = readResult(safeResultJson);
            if (!StringUtils.hasText(result.orderSn())) {
                throw new IllegalStateException("购票结果缺少订单号");
            }
            stateStore.succeed(
                    claimed.actionId(), safeResultJson, result.orderSn(), fingerprint(safeResultJson));
            return new ActionStatusView(
                    claimed.actionId(), AgentActionStatus.SUCCEEDED, result.orderSn(), result, null);
        } catch (RuntimeException ex) {
            // 真实写请求发出后无法证明未成功，因此标记 UNKNOWN 并要求查询订单，绝不自动重试。
            stateStore.markUnknown(
                    claimed.actionId(), "PURCHASE_RESULT_UNKNOWN", ex.getClass().getName());
            throw new AgentChatException(
                    HttpStatus.BAD_GATEWAY,
                    "PURCHASE_RESULT_UNKNOWN",
                    "购票结果暂时无法确认，请先查询本人订单，切勿重复提交");
        }
    }

    /**
     * 查询当前用户购票草案的持久化状态和脱敏结果。
     *
     * @param userId 当前用户标识
     * @param actionId 草案标识
     * @return 操作状态视图
     */
    public ActionStatusView getStatus(String userId, String actionId) {
        return toStatusView(stateStore.get(userId, actionId));
    }

    /**
     * 把草案持久化结果转换为 API 状态视图。
     *
     * @param action 操作草案
     * @return 安全状态视图
     */
    private ActionStatusView toStatusView(ActionDraftEntity action) {
        PurchaseExecutionResult result = StringUtils.hasText(action.getResultJson())
                ? readResult(action.getResultJson()) : null;
        return new ActionStatusView(
                action.getId(), action.getStatus(), action.getResultReference(),
                result, action.getFailureCategory());
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
            if (passenger.seatType() == null || passenger.seatType() < 0 || passenger.seatType() > 14) {
                throw new IllegalArgumentException("席别编码不正确");
            }
            return new PurchasePassenger(passengerId, passenger.seatType());
        }).toList();
        List<String> chooseSeats = requestedPayload.chooseSeats() == null
                ? List.of() : requestedPayload.chooseSeats().stream()
                .map(seat -> requiredText(seat, "座位偏好", 8).toUpperCase())
                .toList();
        if (chooseSeats.size() > normalizedPassengers.size()
                || chooseSeats.stream().anyMatch(seat -> !SEAT_PATTERN.matcher(seat).matches())) {
            throw new IllegalArgumentException("座位偏好格式或数量不正确");
        }
        return new PurchasePayload(trainId, departure, arrival, normalizedPassengers, chooseSeats);
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
                .map(passenger -> passenger.seatType().toString())
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return "购买车次 " + payload.trainId() + "，" + payload.departure() + "→" + payload.arrival()
                + "，乘车人 " + payload.passengers().size() + " 名，席别编码 " + seatTypes;
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
