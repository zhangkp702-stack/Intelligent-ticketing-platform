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
     * @param purchaseActionService 高风险操作草案与确认服务
     */
    public AgentActionController(PurchaseActionService purchaseActionService) {
        this.purchaseActionService = purchaseActionService;
    }

    /**
     * 消费一次性确认令牌并执行已经展示给用户的购票、取消或退票草案。
     *
     * @param userId 网关注入的用户标识
     * @param username 网关注入的用户名
     * @param requestId 前端按钮生成并在重试时复用的请求标识
     * @param idempotencyKey 前端按钮生成并在重试时复用的确认幂等键
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
        if (!hasText(requestId) || !hasText(idempotencyKey)) {
            // 确认重试必须复用稳定标识，禁止服务端每次随机生成导致客户端失去幂等语义。
            throw new AgentChatException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "确认请求必须携带请求标识和幂等键");
        }

        // 按钮重试沿用同一组标识，服务端只负责校验并传递给原子确认状态机。
        return purchaseActionService.confirm(new ConfirmPurchaseCommand(
                requestId.trim(), idempotencyKey.trim(), userId, username,
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

}
