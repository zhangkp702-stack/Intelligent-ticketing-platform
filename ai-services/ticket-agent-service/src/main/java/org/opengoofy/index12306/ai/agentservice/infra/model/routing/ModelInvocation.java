package org.opengoofy.index12306.ai.agentservice.infra.model.routing;

import org.opengoofy.index12306.ai.agentservice.infra.chat.RoutedModelClient;

/**
 * 使用路由选中的模型客户端执行一次同步调用。
 *
 * @param <T> 调用结果类型
 */
@FunctionalInterface
public interface ModelInvocation<T> {

    /**
     * 调用指定候选模型。
     *
     * @param client 已选中的模型客户端
     * @return 模型调用结果
     * @throws Exception 调用失败时抛出
     */
    T invoke(RoutedModelClient client) throws Exception;
}
