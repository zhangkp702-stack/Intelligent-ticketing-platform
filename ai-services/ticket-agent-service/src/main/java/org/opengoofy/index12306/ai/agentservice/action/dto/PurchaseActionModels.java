package org.opengoofy.index12306.ai.agentservice.action.dto;


import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionStatus;

import java.time.Instant;
import java.util.List;

/**
 * 购票草案、确认和脱敏执行结果的数据模型集合。
 */
public final class PurchaseActionModels {

    /**
     * 工具类不允许实例化。
     */
    private PurchaseActionModels() {
    }

    /**
     * @param passengerId 当前用户乘车人标识
     * @param seatType 席别编码
     */
    public record PurchasePassenger(String passengerId, Integer seatType) {
    }

    /**
     * @param trainId 车次内部标识
     * @param departure 出发站名称
     * @param arrival 到达站名称
     * @param departureDate 乘车日期，格式 yyyy-MM-dd
     * @param passengers 乘车人与席别
     * @param chooseSeats 可选座位偏好
     */
    public record PurchasePayload(
            String trainId,
            String departure,
            String arrival,
            String departureDate,
            List<PurchasePassenger> passengers,
            List<String> chooseSeats) {
    }

    /**
     * @param actionId 草案标识
     * @param status 草案状态
     * @param summary 供模型说明的安全摘要
     * @param confirmationExpiresAt 确认截止时间
     */
    public record PurchaseDraftResult(
            String actionId,
            AgentActionStatus status,
            String summary,
            Instant confirmationExpiresAt) {
    }

    /**
     * @param actionId 草案标识
     * @param actionType 操作类型
     * @param status 草案状态
     * @param summary 展示给用户的确认摘要
     * @param confirmationExpiresAt 确认截止时间
     * @param confirmationToken HMAC 确认令牌
     */
    public record ActionConfirmationView(
            String actionId,
            String actionType,
            AgentActionStatus status,
            String summary,
            Instant confirmationExpiresAt,
            String confirmationToken) {
    }

    /**
     * @param requestId 确认请求标识
     * @param idempotencyKey 确认幂等键
     * @param userId 当前用户标识
     * @param username 当前用户名
     * @param actionId 草案标识
     * @param confirmationToken 用户提交的一次性确认令牌
     */
    public record ConfirmPurchaseCommand(
            String requestId,
            String idempotencyKey,
            String userId,
            String username,
            String actionId,
            String confirmationToken) {
    }

    /**
     * @param seatType 席别编码
     * @param carriageNumber 车厢号
     * @param seatNumber 座位号
     * @param realName 乘车人姓名
     * @param ticketType 票种
     * @param amount 金额，单位沿用票务服务定义
     */
    public record PurchasedTicket(
            Integer seatType,
            String carriageNumber,
            String seatNumber,
            String realName,
            Integer ticketType,
            Integer amount) {
    }

    /**
     * @param orderSn 新建订单号
     * @param tickets 脱敏车票明细
     */
    public record PurchaseExecutionResult(String orderSn, List<PurchasedTicket> tickets) {
    }

    /**
     * @param actionId 草案标识
     * @param actionType 操作类型
     * @param status 最终或当前状态
     * @param orderSn 业务订单号
     * @param result 脱敏操作结果
     * @param failureCategory 稳定失败分类
     */
    public record ActionStatusView(
            String actionId,
            String actionType,
            AgentActionStatus status,
            String orderSn,
            Object result,
            String failureCategory) {
    }

    /**
     * @param turnId 产生操作草案的问答轮次
     * @param action 可恢复的操作摘要和可选确认令牌
     * @param execution 当前持久化状态和脱敏结果
     */
    public record RecoverableActionView(
            String turnId,
            ActionConfirmationView action,
            ActionStatusView execution) {
    }

    /**
     * @param confirmationToken SSE 返回的确认令牌
     */
    public record ConfirmPurchaseRequest(String confirmationToken) {
    }
}
