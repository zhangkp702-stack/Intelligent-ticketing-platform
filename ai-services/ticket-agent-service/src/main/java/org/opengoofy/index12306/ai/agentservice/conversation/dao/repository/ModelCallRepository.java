package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ModelCallEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 模型调用持久化审计访问接口。
 */
public interface ModelCallRepository extends JpaRepository<ModelCallEntity, String> {
}
