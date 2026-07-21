package org.opengoofy.index12306.ai.agentservice.infra.chat;

import java.util.Map;
import java.util.Optional;

/**
 * 提供按候选项查找模型客户端的只读注册表。
 */
public interface ModelClientRegistry {

    /**
     * 根据候选项标识查找已经完成配置的模型客户端。
     *
     * @param candidateId 候选项标识
     * @return 找到时返回模型客户端，否则返回空
     */
    Optional<RoutedModelClient> find(String candidateId);

    /**
     * 返回当前已经注册的全部模型客户端。
     *
     * @return 以候选项标识为键的不可变客户端映射
     */
    Map<String, RoutedModelClient> all();
}
