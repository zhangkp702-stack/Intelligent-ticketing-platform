package org.opengoofy.index12306.ai.agentservice.conversation.dao.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ModelCallEntity;

/**
 * 模型调用持久化审计访问接口。
 */
@Mapper
public interface ModelCallRepository extends BaseMapper<ModelCallEntity> {
}
