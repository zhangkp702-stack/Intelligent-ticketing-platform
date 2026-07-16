package org.opengoofy.index12306.ai.agentservice.action;

import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ActionStatusView;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ConfirmPurchaseCommand;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.ConfirmPurchaseRequest;
import org.opengoofy.index12306.ai.agentservice.chat.AgentChatException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 提供高风险智能体操作的显式确认和状态查询接口。
 */
@RestController
@RequestMapping("/api/agent-service/actions")
public class AgentActionController {

    private static final String USER_ID_HEADER = "userId";
    private static final String USERNAME_HEADER = "username";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PurchaseActionService purchaseActionService;

    /**
     * 创建智能体操作控制器。
     *
     * @param purchaseActionService 购票草案与确认服务
     */
    public AgentActionController(PurchaseActionService purchaseActionService) {
        this.purchaseActionService = purchaseActionService;
    }

    /**
     * 消费一次性确认令牌并执行已经展示给用户的购票草案。
     *
     * @param userId 网关注入的用户标识
     * @param username 网关注入的用户名
     * @param requestId 可选请求标识
     * @param idempotencyKey 可选确认幂等键
     * @param actionId 待确认操作标识
     * @param request 包含确认令牌的请求体
     * @return 操作执行后的持久化状态
     */
    @PostMapping("/{actionId}/confirm")
    public ActionStatusView confirm(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestHeader(value = USERNAME_HEADER, required = false) String username,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String actionId,
            @RequestBody ConfirmPurchaseRequest request) {
        if (request == null) {
            throw new AgentChatException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "确认请求体不能为空");
        }

        // 请求标识和幂等键由服务端补齐，保证客户端重试时可明确复用同一确认语义。
        String actualRequestId = hasText(requestId) ? requestId.trim() : randomRequestId();
        String actualIdempotencyKey = hasText(idempotencyKey)
                ? idempotencyKey.trim() : actualRequestId;
        return purchaseActionService.confirm(new ConfirmPurchaseCommand(
                actualRequestId, actualIdempotencyKey, userId, username,
                actionId, request.confirmationToken()));
    }

    /**
     * 查询当前用户指定操作的最新持久化状态。
     *
     * @param userId 网关注入的用户标识
     * @param actionId 操作标识
     * @return 操作状态和可选脱敏结果
     */
    @GetMapping("/{actionId}")
    public ActionStatusView getStatus(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String actionId) {
        // 状态服务会再次校验操作归属，避免通过操作标识越权读取其他用户结果。
        return purchaseActionService.getStatus(userId, actionId);
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
        // 去除连字符后长度固定，可同时作为默认确认幂等键。
        return UUID.randomUUID().toString().replace("-", "");
    }
}
