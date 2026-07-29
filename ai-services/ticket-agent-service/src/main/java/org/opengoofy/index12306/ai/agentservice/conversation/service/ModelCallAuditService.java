package org.opengoofy.index12306.ai.agentservice.conversation.service;

import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ModelCallEntity;

/**
 * 定义不包含提示词和回答正文的模型调用审计写入能力。
 */
public interface ModelCallAuditService {

    /**
     * 持久化模型调用稳定元数据。
     *
     * @param data 模型调用稳定元数据
     * @return 持久化审计标识
     */
    String record(ModelCallEntity.ModelCallData data);
}
