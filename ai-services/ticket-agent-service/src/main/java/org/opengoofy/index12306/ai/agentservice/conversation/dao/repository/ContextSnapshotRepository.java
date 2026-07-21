package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ContextSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 上下文快照元数据持久化访问接口。
 */
public interface ContextSnapshotRepository extends JpaRepository<ContextSnapshotEntity, String> {

    /**
     * 按请求标识查询已经生成的上下文快照，用于保证请求重试幂等。
     *
     * @param requestId 请求标识
     * @return 已存在的上下文快照
     */
    Optional<ContextSnapshotEntity> findByRequestId(String requestId);
}
