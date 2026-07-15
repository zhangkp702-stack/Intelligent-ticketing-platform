package org.opengoofy.index12306.ai.agentservice.memory.repository;

import org.opengoofy.index12306.ai.agentservice.memory.domain.ModelCallEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 模型调用持久化审计访问接口。
 */
public interface ModelCallRepository extends JpaRepository<ModelCallEntity, String> {
}
