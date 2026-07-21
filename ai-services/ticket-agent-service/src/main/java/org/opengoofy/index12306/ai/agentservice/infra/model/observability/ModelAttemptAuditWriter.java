package org.opengoofy.index12306.ai.agentservice.infra.model.observability;

/**
 * 将单次模型尝试写入外部审计存储的扩展端口。
 */
public interface ModelAttemptAuditWriter {

    /**
     * 持久化不含提示词和响应正文的模型尝试元数据。
     *
     * @param event 模型尝试事件
     * @return 持久化后的审计记录标识
     */
    String record(ModelAttemptEvent event);
}
