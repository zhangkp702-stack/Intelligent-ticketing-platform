package org.opengoofy.index12306.ai.agentservice.memory.repository;

import org.opengoofy.index12306.ai.agentservice.memory.domain.ContextRouteLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 主题路由审计持久化访问接口。
 */
public interface ContextRouteLogRepository extends JpaRepository<ContextRouteLogEntity, String> {

    /**
     * 按请求标识查询已有的主题路由日志，用于保证审计写入幂等。
     *
     * @param requestId 请求标识
     * @return 已存在的主题路由日志
     */
    Optional<ContextRouteLogEntity> findByRequestId(String requestId);
}
