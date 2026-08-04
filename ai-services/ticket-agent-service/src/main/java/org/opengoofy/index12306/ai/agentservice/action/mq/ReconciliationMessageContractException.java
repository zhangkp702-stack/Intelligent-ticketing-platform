package org.opengoofy.index12306.ai.agentservice.action.mq;

/**
 * 表示对账消息不满足当前消费者可安全处理的契约。
 */
public class ReconciliationMessageContractException extends IllegalArgumentException {

    /**
     * 创建不包含消息正文的契约异常，避免日志记录潜在敏感载荷。
     *
     * @param message 稳定的失败原因
     */
    public ReconciliationMessageContractException(String message) {
        // 消费端只记录分类和受控文本，原始消息由 Broker 的审计能力保存。
        super(message);
    }
}
