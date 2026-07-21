package org.opengoofy.index12306.ai.agentservice.action.service;

import org.opengoofy.index12306.ai.agentservice.action.mcp.TicketOperationPreviewExecutor;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.CancellationExecutionResult;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.CancellationPayload;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.CancellationPreview;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.RefundExecutionResult;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.RefundPayload;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.RefundPreview;
import org.opengoofy.index12306.ai.agentservice.action.dto.TicketOperationActionModels.TicketOperationDraftResult;
import org.opengoofy.index12306.ai.agentservice.action.config.AgentActionProperties;
import org.opengoofy.index12306.ai.agentservice.action.dao.entity.ActionDraftEntity;
import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;
import org.opengoofy.index12306.ai.agentservice.chat.exception.AgentChatException;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 使用可信业务预览创建取消和退票草案，并在确认前检查关键状态是否变化。
 */
@Service
public class TicketOperationActionService {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_REFUND_ITEMS = 5;

    private final ActionStateStore stateStore;
    private final ObjectProvider<TicketOperationPreviewExecutor> previewExecutorProvider;
    private final AgentActionProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 创建取消和退票草案服务。
     *
     * @param stateStore 操作状态事务服务
     * @param previewExecutorProvider 可信 MCP 预览执行器
     * @param properties 操作确认配置
     * @param objectMapper JSON 序列化器
     * @param clock 统一时钟
     */
    public TicketOperationActionService(
            ActionStateStore stateStore,
            ObjectProvider<TicketOperationPreviewExecutor> previewExecutorProvider,
            AgentActionProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.stateStore = stateStore;
        this.previewExecutorProvider = previewExecutorProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 读取实时取消条件并创建不可执行的取消订单草案。
     *
     * @param context 已验证的对话上下文
     * @param requestedOrderSn 模型选择的当前用户订单号
     * @return 不包含确认令牌的安全草案结果
     */
    public TicketOperationDraftResult prepareCancellation(
            AgentRequestContext context,
            String requestedOrderSn) {
        String orderSn = identifier(requestedOrderSn, "订单号");

        // 是否允许取消和当前状态完全采用票务服务预检查，模型不能自行声明。
        CancellationPreview preview = previewExecutor().previewCancellation(context, orderSn);
        if (!orderSn.equals(preview.orderSn()) || !Boolean.TRUE.equals(preview.canCancel())) {
            throw new IllegalArgumentException(reason(preview.reason(), "当前订单不允许取消"));
        }
        if (preview.orderStatus() == null) {
            throw new IllegalStateException("取消预览缺少订单状态");
        }
        CancellationPayload payload = new CancellationPayload(orderSn, preview.orderStatus());
        ActionDraftEntity action = createDraft(context, AgentActionType.TICKET_CANCEL, payload);
        return new TicketOperationDraftResult(
                action.getId(), action.getStatus(), summary(payload), action.getConfirmationExpiresAt());
    }

    /**
     * 读取实时可退范围和金额并创建不可执行的退票草案。
     *
     * @param context 已验证的对话上下文
     * @param requestedOrderSn 模型选择的当前用户订单号
     * @param requestedType 退款类型
     * @param requestedOrderItemIds 部分退款子订单记录标识
     * @return 不包含确认令牌的安全草案结果
     */
    public TicketOperationDraftResult prepareRefund(
            AgentRequestContext context,
            String requestedOrderSn,
            Integer requestedType,
            List<String> requestedOrderItemIds) {
        String orderSn = identifier(requestedOrderSn, "订单号");
        Integer type = refundType(requestedType);
        List<String> orderItemIds = normalizeItemIds(type, requestedOrderItemIds);

        // 真实可退明细和金额由票务服务生成，草案只保存该可信快照。
        RefundPreview preview = previewExecutor().previewRefund(context, orderSn, type, orderItemIds);
        if (!orderSn.equals(preview.orderSn())
                || !type.equals(preview.type())
                || !Boolean.TRUE.equals(preview.refundable())) {
            throw new IllegalArgumentException(reason(preview.reason(), "当前选择不允许退票"));
        }
        if (preview.refundAmount() == null || preview.refundAmount() < 0 || preview.items() == null) {
            throw new IllegalStateException("退票预览缺少金额或车票明细");
        }
        List<String> selectedItemIds = preview.items().stream()
                .map(item -> identifier(item.orderItemId(), "子订单记录标识"))
                .sorted()
                .toList();
        if (selectedItemIds.isEmpty() || (type == 0 && !selectedItemIds.equals(orderItemIds))) {
            throw new IllegalStateException("退票预览范围与请求不一致");
        }
        RefundPayload payload = new RefundPayload(
                orderSn, type, selectedItemIds, preview.refundAmount());
        ActionDraftEntity action = createDraft(context, AgentActionType.TICKET_REFUND, payload);
        return new TicketOperationDraftResult(
                action.getId(), action.getStatus(), summary(payload), action.getConfirmationExpiresAt());
    }

    /**
     * 在消费确认令牌前重新预览操作，并拒绝状态、范围或金额已经变化的草案。
     *
     * @param action 待确认操作草案
     * @param context 当前确认请求上下文
     */
    public void revalidate(ActionDraftEntity action, AgentRequestContext context) {
        if (action.getActionType() == AgentActionType.TICKET_CANCEL) {
            // 取消操作必须保持同一订单状态且仍然允许取消。
            CancellationPayload payload = readCancellationPayload(action.getPayloadJson());
            CancellationPreview preview = previewExecutor().previewCancellation(context, payload.orderSn());
            if (!payload.orderSn().equals(preview.orderSn())
                    || !payload.orderStatus().equals(preview.orderStatus())
                    || !Boolean.TRUE.equals(preview.canCancel())) {
                throw changed();
            }
            return;
        }
        if (action.getActionType() == AgentActionType.TICKET_REFUND) {
            // 退票操作必须保持相同车票范围和预计金额，防止用户确认了过期快照。
            RefundPayload payload = readRefundPayload(action.getPayloadJson());
            RefundPreview preview = previewExecutor().previewRefund(
                    context, payload.orderSn(), payload.type(), payload.orderItemIds());
            List<String> currentItemIds = preview.items() == null ? List.of() : preview.items().stream()
                    .map(item -> item.orderItemId())
                    .sorted()
                    .toList();
            if (!payload.orderSn().equals(preview.orderSn())
                    || !payload.type().equals(preview.type())
                    || !Boolean.TRUE.equals(preview.refundable())
                    || !payload.expectedRefundAmount().equals(preview.refundAmount())
                    || !payload.orderItemIds().equals(currentItemIds)) {
                throw changed();
            }
            return;
        }
        throw new IllegalArgumentException("操作类型不属于取消或退票");
    }

    /**
     * 根据草案类型生成用户确认时展示的摘要。
     *
     * @param action 操作草案
     * @return 不包含敏感信息的确认摘要
     */
    public String summary(ActionDraftEntity action) {
        return switch (action.getActionType()) {
            case TICKET_CANCEL -> summary(readCancellationPayload(action.getPayloadJson()));
            case TICKET_REFUND -> summary(readRefundPayload(action.getPayloadJson()));
            default -> throw new IllegalArgumentException("操作类型不属于取消或退票");
        };
    }

    /**
     * 解析取消或退票的持久化脱敏执行结果。
     *
     * @param actionType 操作类型
     * @param json 结果 JSON
     * @return 对应操作结果
     */
    public Object readResult(AgentActionType actionType, String json) {
        try {
            // 状态接口按动作类型恢复稳定结果结构，避免向客户端暴露原始下游响应。
            return switch (actionType) {
                case TICKET_CANCEL -> objectMapper.readValue(json, CancellationExecutionResult.class);
                case TICKET_REFUND -> objectMapper.readValue(json, RefundExecutionResult.class);
                default -> throw new IllegalArgumentException("操作类型不属于取消或退票");
            };
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("订单操作执行结果格式无效", ex);
        }
    }

    /**
     * 从取消或退票结果中读取订单号作为审计关联标识。
     *
     * @param actionType 操作类型
     * @param result 已解析结果
     * @return 订单号
     */
    public String resultReference(AgentActionType actionType, Object result) {
        return switch (actionType) {
            case TICKET_CANCEL -> ((CancellationExecutionResult) result).orderSn();
            case TICKET_REFUND -> ((RefundExecutionResult) result).orderSn();
            default -> throw new IllegalArgumentException("操作类型不属于取消或退票");
        };
    }

    /**
     * 返回操作结果不确定时使用的稳定失败分类。
     *
     * @param actionType 操作类型
     * @return UNKNOWN 分类
     */
    public String unknownCategory(AgentActionType actionType) {
        return actionType == AgentActionType.TICKET_CANCEL
                ? "CANCELLATION_RESULT_UNKNOWN" : "REFUND_RESULT_UNKNOWN";
    }

    /**
     * 创建通用操作草案并计算不可变参数指纹。
     *
     * @param context 已验证请求上下文
     * @param actionType 操作类型
     * @param payload 规范化草案参数
     * @return 新建或幂等复用的草案
     */
    private ActionDraftEntity createDraft(
            AgentRequestContext context,
            AgentActionType actionType,
            Object payload) {
        String payloadJson = writeJson(payload);
        String payloadHash = fingerprint(payloadJson);

        // 草案仅写入 Agent 独立数据库，不调用取消或退款写接口。
        return stateStore.createDraft(
                context,
                actionType,
                payloadJson,
                payloadHash,
                clock.instant().plus(properties.confirmationTtl()));
    }

    /**
     * 获取已启用的可信 MCP 预览执行器。
     *
     * @return 预览执行器
     */
    private TicketOperationPreviewExecutor previewExecutor() {
        TicketOperationPreviewExecutor executor = previewExecutorProvider.getIfAvailable();
        if (executor == null) {
            throw new AgentChatException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PREVIEW_MCP_UNAVAILABLE",
                    "订单操作预览服务暂时不可用");
        }
        return executor;
    }

    /**
     * 生成取消订单确认摘要。
     *
     * @param payload 取消草案
     * @return 确认摘要
     */
    private String summary(CancellationPayload payload) {
        return "取消订单 " + payload.orderSn() + "，当前订单状态 " + payload.orderStatus();
    }

    /**
     * 生成退票确认摘要。
     *
     * @param payload 退票草案
     * @return 确认摘要
     */
    private String summary(RefundPayload payload) {
        String scope = payload.type() == 0
                ? "部分退票 " + payload.orderItemIds().size() + " 张"
                : "全部退票 " + payload.orderItemIds().size() + " 张";
        return "订单 " + payload.orderSn() + "，" + scope
                + "，预计退款金额 " + payload.expectedRefundAmount();
    }

    /**
     * 规范化部分退票子订单记录标识。
     *
     * @param type 退款类型
     * @param requestedIds 原始记录标识
     * @return 排序后的唯一标识
     */
    private List<String> normalizeItemIds(Integer type, List<String> requestedIds) {
        List<String> values = requestedIds == null ? List.of() : requestedIds.stream()
                .map(value -> identifier(value, "子订单记录标识"))
                .sorted()
                .toList();
        if (values.size() > MAX_REFUND_ITEMS || (type == 0 && values.isEmpty())) {
            throw new IllegalArgumentException("部分退票必须选择 1 到 5 张车票");
        }

        // 重复记录会导致确认摘要与实际退款范围产生歧义，因此在草案阶段拒绝。
        Set<String> uniqueIds = new HashSet<>(values);
        if (uniqueIds.size() != values.size()) {
            throw new IllegalArgumentException("退票车票标识不能重复");
        }
        return values;
    }

    /**
     * 校验退款类型。
     *
     * @param value 原始退款类型
     * @return 有效退款类型
     */
    private Integer refundType(Integer value) {
        if (value == null || (value != 0 && value != 1)) {
            throw new IllegalArgumentException("退款类型必须是 0 或 1");
        }
        return value;
    }

    /**
     * 校验业务标识格式。
     *
     * @param value 原始标识
     * @param field 字段说明
     * @return 规范化标识
     */
    private String identifier(String value, String field) {
        if (!StringUtils.hasText(value) || !IDENTIFIER_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(field + "格式不正确");
        }
        return value.trim();
    }

    /**
     * 序列化规范操作参数。
     *
     * @param payload 操作参数
     * @return JSON 文本
     */
    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化订单操作草案", ex);
        }
    }

    /**
     * 反序列化取消草案参数。
     *
     * @param json 参数 JSON
     * @return 取消草案
     */
    private CancellationPayload readCancellationPayload(String json) {
        try {
            return objectMapper.readValue(json, CancellationPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("取消订单草案参数损坏", ex);
        }
    }

    /**
     * 反序列化退票草案参数。
     *
     * @param json 参数 JSON
     * @return 退票草案
     */
    private RefundPayload readRefundPayload(String json) {
        try {
            return objectMapper.readValue(json, RefundPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("退票草案参数损坏", ex);
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
            // 参数指纹进入确认令牌和 MCP 签名，确保确认后不能替换订单或退票范围。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少 SHA-256", ex);
        }
    }

    /**
     * 在下游未返回原因时选择稳定提示。
     *
     * @param actual 下游原因
     * @param fallback 默认提示
     * @return 用户可读原因
     */
    private String reason(String actual, String fallback) {
        return StringUtils.hasText(actual) ? actual : fallback;
    }

    /**
     * 创建草案快照已变化的冲突异常。
     *
     * @return HTTP 409 异常
     */
    private AgentChatException changed() {
        return new AgentChatException(
                HttpStatus.CONFLICT,
                "ACTION_PREVIEW_CHANGED",
                "订单状态、退票范围或金额已经变化，请重新生成操作草案");
    }
}
