package org.opengoofy.index12306.ai.agentservice.chat.execution.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.chat.execution.dao.entity.TaskExecutionEntity;

import java.util.List;
import java.util.Optional;

/**
 * 单轮任务计划和执行检查点持久化访问接口。
 */
@Mapper
public interface TaskExecutionRepository extends BaseMapper<TaskExecutionEntity> {

    /**
     * 按用户原始任务顺序加载一轮已经固化的服务端计划。
     *
     * @param turnId 轮次标识
     * @return 任务检查点列表
     */
    @Select("SELECT * FROM t_agent_task_execution WHERE turn_id = #{turnId} ORDER BY sequence_no ASC")
    List<TaskExecutionEntity> findByTurnIdOrderBySequenceNoAsc(@Param("turnId") String turnId);

    /**
     * 使用数据库写锁读取单个任务，保护领取和终态提交。
     *
     * @param taskId 服务端任务标识
     * @return 锁定的任务检查点
     */
    @Select("SELECT * FROM t_agent_task_execution WHERE id = #{taskId} FOR UPDATE")
    Optional<TaskExecutionEntity> findLockedById(@Param("taskId") String taskId);
}
