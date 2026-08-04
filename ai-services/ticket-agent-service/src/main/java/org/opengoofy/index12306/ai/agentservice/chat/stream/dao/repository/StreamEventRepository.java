package org.opengoofy.index12306.ai.agentservice.chat.stream.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.opengoofy.index12306.ai.agentservice.chat.stream.dao.entity.StreamEventEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 可重放 SSE 事件持久化访问接口。
 */
@Mapper
public interface StreamEventRepository extends BaseMapper<StreamEventEntity> {

    /**
     * 查询客户端游标之后的事件。
     *
     * @param turnId 服务端轮次标识
     * @param afterSequence 客户端最后确认的事件序号
     * @param limit 单次读取上限
     * @return 按序号升序排列的事件
     */
    @Select("SELECT * FROM t_agent_stream_event WHERE turn_id = #{turnId} "
            + "AND event_sequence > #{afterSequence} ORDER BY event_sequence ASC LIMIT #{limit}")
    List<StreamEventEntity> findAfterSequence(
            @Param("turnId") String turnId,
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit);

    /**
     * 查询轮次唯一的终态事件。
     *
     * @param turnId 服务端轮次标识
     * @return 已发布的 DONE 或 ERROR
     */
    @Select("SELECT * FROM t_agent_stream_event WHERE turn_id = #{turnId} AND terminal = TRUE "
            + "ORDER BY event_sequence ASC LIMIT 1")
    Optional<StreamEventEntity> findTerminal(@Param("turnId") String turnId);

    /**
     * 查询已经进入业务终态且超过保留期的最旧事件标识。
     *
     * @param cutoff 事件创建时间上界
     * @param limit 单批清理上限
     * @return 按创建时间升序排列的待清理事件标识
     */
    @Select("SELECT stream_event.id FROM t_agent_stream_event stream_event "
            + "JOIN t_agent_turn turn_state ON turn_state.id = stream_event.turn_id "
            + "WHERE stream_event.created_at < #{cutoff} "
            + "AND turn_state.status IN ('COMPLETED', 'FAILED', 'CANCELLED') "
            + "ORDER BY stream_event.created_at ASC, stream_event.id ASC LIMIT #{limit}")
    List<String> findExpiredTerminalEventIds(
            @Param("cutoff") Instant cutoff,
            @Param("limit") int limit);
}
