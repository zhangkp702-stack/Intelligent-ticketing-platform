package org.opengoofy.index12306.ai.agentservice.action.service;

import org.opengoofy.index12306.ai.agentservice.action.enums.AgentActionType;

import java.util.List;
import java.util.Optional;

/**
 * 管理 UNKNOWN 操作的事务 Outbox、MQ Inbox 领取和权威结果收口。
 */
public interface ActionReconciliationService {

    /**
     * 在操作转为 UNKNOWN 的同一事务中创建唯一对账事件。
     *
     * @param actionId 操作草案标识
     */
    void request(String actionId);

    /**
     * 查询有限批次的待发布事件。
     *
     * @return 待发布事件快照
     */
    List<PendingEvent> pendingEvents();

    /**
     * 记录 RocketMQ 已经持久化接收当前事件。
     *
     * @param eventId 事件标识
     * @param eventVersion 事件版本
     * @param messageId MQ 消息标识
     */
    void markPublished(String eventId, long eventVersion, String messageId);

    /**
     * 恢复消费租约过期和重试到期事件。
     *
     * @return 本次恢复数量
     */
    int recoverExpired();

    /**
     * 幂等领取 MQ 事件并冻结对账输入。
     *
     * @param eventId 事件标识
     * @param eventVersion 事件版本
     * @param workerId 消费实例标识
     * @return 成功领取时的不可变工作项
     */
    Optional<WorkItem> claim(String eventId, long eventVersion, String workerId);

    /**
     * 使用 ticket-service 权威状态结束本次查询。
     *
     * @param eventId 事件标识
     * @param result 下游操作状态
     * @return 是否已经得到成功或失败终态
     */
    boolean complete(String eventId, DownstreamResult result);

    /**
     * 记录对账查询异常并决定是否继续重试。
     *
     * @param eventId 事件标识
     * @param category 稳定失败分类
     * @param safeMessage 安全错误说明
     * @return 是否仍允许重试
     */
    boolean fail(String eventId, String category, String safeMessage);

    /**
     * @param eventId Outbox 事件标识
     * @param actionId 操作标识
     * @param eventVersion 事件版本
     */
    record PendingEvent(String eventId, String actionId, long eventVersion) {
    }

    /**
     * @param eventId 事件标识
     * @param eventVersion 事件版本
     * @param actionId 操作标识
     * @param actionType 操作类型
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param payloadJson 不可变草案 JSON
     * @param payloadHash 草案指纹
     */
    record WorkItem(
            String eventId,
            long eventVersion,
            String actionId,
            AgentActionType actionType,
            String userId,
            String conversationId,
            String turnId,
            String payloadJson,
            String payloadHash) {
    }

    /**
     * @param actionId 下游操作标识
     * @param operationType 下游操作类型
     * @param status 下游持久化状态
     * @param safeResultJson 成功时的脱敏结果 JSON
     * @param failureMessage 明确失败原因
     */
    record DownstreamResult(
            String actionId,
            String operationType,
            DownstreamStatus status,
            String safeResultJson,
            String failureMessage) {
    }

    /**
     * ticket-service 对账接口允许返回的稳定状态。
     */
    enum DownstreamStatus {
        PROCESSING,
        SUCCEEDED,
        FAILED
    }
}
