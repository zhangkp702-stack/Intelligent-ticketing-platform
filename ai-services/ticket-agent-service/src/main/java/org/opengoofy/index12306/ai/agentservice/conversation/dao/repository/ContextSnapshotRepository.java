package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ContextSnapshotEntity;

import java.util.Optional;

/**
 * 上下文快照元数据持久化访问接口。
 */
@Mapper
public interface ContextSnapshotRepository extends BaseMapper<ContextSnapshotEntity> {

    /**
     * 按请求标识查询已经生成的上下文快照，用于保证请求重试幂等。
     *
     * @param requestId 请求标识
     * @return 已存在的上下文快照
     */
    @Select("SELECT * FROM t_agent_context_snapshot WHERE request_id = #{requestId}")
    Optional<ContextSnapshotEntity> findByRequestId(@Param("requestId") String requestId);
}
