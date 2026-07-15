package org.opengoofy.index12306.ai.agentservice.memory.repository;

import org.opengoofy.index12306.ai.agentservice.memory.domain.MemorySummaryEntity;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MemorySummaryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 主题摘要版本持久化访问接口。
 */
public interface MemorySummaryRepository extends JpaRepository<MemorySummaryEntity, String> {

    /**
     * 查询主题当前活动的最高摘要版本。
     *
     * @param topicId 主题标识
     * @param status 摘要状态
     * @return 当前活动摘要
     */
    Optional<MemorySummaryEntity> findFirstByTopicIdAndStatusOrderByVersionNoDesc(
            String topicId,
            MemorySummaryStatus status);
}
