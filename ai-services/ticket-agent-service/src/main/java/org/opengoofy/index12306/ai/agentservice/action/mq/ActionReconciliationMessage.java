package org.opengoofy.index12306.ai.agentservice.action.mq;

import org.opengoofy.index12306.ai.agentservice.action.service.ActionReconciliationService;

import java.time.Instant;

/**
 * 动作对账事件的跨进程消息契约。
 *
 * <p>消息中的标识仅用于路由和快速拒绝；消费者必须再与本地 Outbox 记录交叉校验，
 * 不能将消息体视为业务事实。</p>
 *
 * @param contractVersion 消息契约版本，0 表示部署前遗留消息，1 表示当前版本
 * @param eventId 数据库 Outbox 事件标识
 * @param eventNamespace 事件业务域
 * @param eventType 事件类型
 * @param actionId 操作草案标识
 * @param eventVersion 事件版本
 * @param createdAt 消息创建时间
 */
public record ActionReconciliationMessage(
        int contractVersion,
        String eventId,
        String eventNamespace,
        String eventType,
        String actionId,
        long eventVersion,
        Instant createdAt) {

    /** 当前发布消息使用的契约版本。 */
    public static final int CURRENT_CONTRACT_VERSION = 1;

    /** 兼容滚动发布期间仍可能滞留在 Broker 中的旧消息。 */
    public static final int LEGACY_CONTRACT_VERSION = 0;

    /** 动作进入 UNKNOWN 后触发权威查询的稳定事件类型。 */
    public static final String EVENT_TYPE = "ACTION_RECONCILIATION_REQUESTED";

    /**
     * 规范化新旧消息的缺省字段，并拒绝不受支持的协议版本。
     *
     * @param contractVersion 消息契约版本
     * @param eventId Outbox 事件标识
     * @param eventNamespace 事件业务域
     * @param eventType 事件类型
     * @param actionId 操作草案标识
     * @param eventVersion 事件版本
     * @param createdAt 消息创建时间
     */
    public ActionReconciliationMessage {
        // 旧消息没有命名空间和事件类型；仅在滚动发布窗口内补成这一类事件的固定值。
        if (contractVersion == LEGACY_CONTRACT_VERSION) {
            eventNamespace = ActionReconciliationService.EVENT_NAMESPACE;
            eventType = EVENT_TYPE;
        }
        if (contractVersion != LEGACY_CONTRACT_VERSION && contractVersion != CURRENT_CONTRACT_VERSION) {
            throw new ReconciliationMessageContractException("Unsupported reconciliation message contract version");
        }
        if (isBlank(eventId) || isBlank(eventNamespace) || isBlank(eventType) || isBlank(actionId)
                || eventVersion <= 0 || createdAt == null) {
            throw new ReconciliationMessageContractException("Invalid reconciliation message contract");
        }
    }

    /**
     * 根据已领取的 Outbox 快照创建当前版本的传输消息。
     *
     * @param event 已持久化的待发布事件
     * @return 带完整路由与校验字段的消息
     */
    public static ActionReconciliationMessage from(ActionReconciliationService.PendingEvent event) {
        // 发送端只从持久化 Outbox 快照取值，避免将内存中的动作状态重新拼接成另一份事实。
        return new ActionReconciliationMessage(
                CURRENT_CONTRACT_VERSION,
                event.eventId(),
                ActionReconciliationService.EVENT_NAMESPACE,
                EVENT_TYPE,
                event.actionId(),
                event.eventVersion(),
                event.createdAt());
    }

    /**
     * 判断文本字段是否为空白。
     *
     * @param value 待校验文本
     * @return 是否为空白
     */
    private static boolean isBlank(String value) {
        // 使用 JDK 校验保持消息契约可在不依赖 Spring 上下文的场景中反序列化和测试。
        return value == null || value.isBlank();
    }
}
