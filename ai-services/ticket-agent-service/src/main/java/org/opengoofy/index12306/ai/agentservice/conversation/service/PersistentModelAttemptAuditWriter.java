package org.opengoofy.index12306.ai.agentservice.conversation.service;

import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ModelCallEntity;
import org.opengoofy.index12306.ai.agentservice.infra.model.observability.ModelAttemptAuditWriter;
import org.opengoofy.index12306.ai.agentservice.infra.model.observability.ModelAttemptContext;
import org.opengoofy.index12306.ai.agentservice.infra.model.observability.ModelAttemptEvent;
import org.springframework.stereotype.Component;

/**
 * 将模型路由的每次候选尝试持久化到智能体审计表。
 */
@Component
public class PersistentModelAttemptAuditWriter implements ModelAttemptAuditWriter {

    private final ModelCallAuditService auditService;

    /**
     * 创建模型尝试持久化写入器。
     *
     * @param auditService 模型调用审计服务
     */
    public PersistentModelAttemptAuditWriter(ModelCallAuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 将不含提示词和响应正文的模型尝试事件转换为审计实体并独立提交。
     *
     * @param event 模型尝试事件
     * @return 持久化后的审计记录标识
     */
    @Override
    public String record(ModelAttemptEvent event) {
        // 空上下文用于内部调用，仍保留模型、结果和耗时等基础审计字段。
        ModelAttemptContext context = event.context() == null
                ? ModelAttemptContext.empty()
                : event.context();
        ModelCallEntity.ModelCallData data = new ModelCallEntity.ModelCallData(
                context.requestId(),
                context.conversationId(),
                context.turnId(),
                event.role(),
                event.providerId(),
                event.candidateId(),
                event.modelId(),
                event.fallbackIndex() + 1,
                event.fallbackIndex(),
                event.outcome(),
                event.failureCategory(),
                event.durationMillis(),
                null,
                null,
                null,
                event.firstChunkEmitted(),
                event.exceptionType());

        // 使用独立事务保存审计，使业务事务回滚时仍可诊断模型尝试结果。
        return auditService.record(data);
    }
}
