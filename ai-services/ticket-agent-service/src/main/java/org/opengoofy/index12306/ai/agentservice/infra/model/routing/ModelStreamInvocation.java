package org.opengoofy.index12306.ai.agentservice.infra.model.routing;

import org.opengoofy.index12306.ai.agentservice.infra.chat.RoutedModelClient;
import reactor.core.publisher.Flux;

/**
 * 使用路由选中的模型客户端创建一次流式调用。
 *
 * @param <T> 流式数据块类型
 */
@FunctionalInterface
public interface ModelStreamInvocation<T> {

    /**
     * 创建指定候选模型的响应流。
     *
     * @param client 已选中的模型客户端
     * @return 模型响应流
     */
    Flux<T> invoke(RoutedModelClient client);
}
