package org.opengoofy.index12306.ai.agentservice.action.controller;

import org.opengoofy.index12306.ai.agentservice.action.config.ActionManualReviewProperties;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;
import org.opengoofy.index12306.ai.agentservice.chat.exception.AgentChatException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供默认关闭的内部人工对账重启入口。
 *
 * <p>该入口不能写入成功或失败结论，也不会重放原始购票、取消或退款请求。</p>
 */
@RestController
@RequestMapping("/internal/api/agent-service/manual-reviews")
@ConditionalOnProperty(
        prefix = "index12306.agent.action.manual-review",
        name = "enabled",
        havingValue = "true")
public class ActionManualReviewController {

    private static final String OPERATOR_ID_HEADER = "X-Operator-Id";
    private static final String MANUAL_REVIEW_SECRET_HEADER = "X-Manual-Reconciliation-Secret";

    private final ActionManualReviewProperties properties;
    private final ActionReconciliationService reconciliationService;

    /**
     * 创建受保护的人工复核控制器。
     *
     * @param properties 人工入口启用与密钥配置
     * @param reconciliationService 对账状态服务
     */
    public ActionManualReviewController(
            ActionManualReviewProperties properties,
            ActionReconciliationService reconciliationService) {
        this.properties = properties;
        this.reconciliationService = reconciliationService;
    }

    /**
     * 由受权操作员重新安排同一动作的权威只读查询。
     *
     * @param actionId 进入人工复核的动作标识
     * @param operatorId 网关或运维平台注入的操作员标识
     * @param secret 内部人工入口独立密钥
     * @param request 包含人工重新核对原因的请求体
     * @return 已重新调度的动作摘要
     */
    @PostMapping("/{actionId}/resume")
    public ActionReconciliationService.ManualReviewResumeResult resume(
            @PathVariable String actionId,
            @RequestHeader(OPERATOR_ID_HEADER) String operatorId,
            @RequestHeader(MANUAL_REVIEW_SECRET_HEADER) String secret,
            @RequestBody ResumeManualReviewRequest request) {
        // 先验证独立密钥；不能依赖可伪造的操作员文本头作为权限依据。
        if (!properties.matches(secret)) {
            throw new AgentChatException(HttpStatus.FORBIDDEN, "MANUAL_REVIEW_FORBIDDEN", "人工复核权限不足");
        }
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new AgentChatException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "人工复核原因不能为空");
        }

        // 服务层会同步恢复命令、Inbox 和 Outbox，但最终状态只能由下游权威查询结果确定。
        return reconciliationService.resumeManualReview(actionId, operatorId, request.reason().trim());
    }

    /**
     * @param reason 重新开启只读对账的人工原因
     */
    public record ResumeManualReviewRequest(String reason) {
    }
}
