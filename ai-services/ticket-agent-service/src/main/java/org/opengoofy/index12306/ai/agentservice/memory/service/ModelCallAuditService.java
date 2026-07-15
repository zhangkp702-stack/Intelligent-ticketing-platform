package org.opengoofy.index12306.ai.agentservice.memory.service;

import org.opengoofy.index12306.ai.agentservice.memory.domain.ModelCallEntity;
import org.opengoofy.index12306.ai.agentservice.memory.repository.ModelCallRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 持久化带会话上下文的模型调用审计元数据。
 */
@Service
public class ModelCallAuditService {

    private final ModelCallRepository repository;
    private final Clock clock;

    /**
     * 创建模型调用审计服务。
     *
     * @param repository 模型调用仓储
     * @param clock 统一时钟
     */
    public ModelCallAuditService(ModelCallRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 保存不含提示词、回答正文和密钥的模型调用元数据。
     *
     * @param data 模型调用稳定元数据
     * @return 持久化审计标识
     */
    @Transactional
    public String record(ModelCallEntity.ModelCallData data) {
        // 持久化入口只接受受限字段记录，避免调用方误写用户正文。
        ModelCallEntity entity = ModelCallEntity.create(data, clock.instant());
        return repository.save(entity).getId();
    }
}
