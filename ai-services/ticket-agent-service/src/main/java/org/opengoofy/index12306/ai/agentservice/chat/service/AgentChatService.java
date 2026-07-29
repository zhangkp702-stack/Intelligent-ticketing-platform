package org.opengoofy.index12306.ai.agentservice.chat.service;

import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCancelRequest;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 定义智能体会话创建、对话执行和取消能力。
 */
public interface AgentChatService {

    /**
     * 为当前用户创建独立会话。
     *
     * @param userId 用户标识
     * @param title 可选会话标题
     * @return 新会话标识
     */
    String createConversation(String userId, String title);

    /**
     * 执行一轮完整对话并返回流式事件。
     *
     * @param command 对话命令
     * @return SSE 或普通接口可消费的事件流
     */
    Flux<ChatEvent> stream(ChatCommand command);

    /**
     * 取消当前用户正在运行的对话轮次。
     *
     * @param userId 用户标识
     * @param request 取消请求
     * @return 找到并取消运行中轮次时返回 true
     */
    boolean cancel(String userId, ChatCancelRequest request);

    /**
     * 聚合流式事件并返回完整对话结果。
     *
     * @param command 对话命令
     * @return 完整对话结果
     */
    Mono<ChatResult> chat(ChatCommand command);

    /**
     * 将执行异常转换为稳定的前端错误事件。
     *
     * @param command 当前对话命令
     * @param exception 执行异常
     * @return 不暴露内部异常正文的错误事件
     */
    ChatEvent toErrorEvent(ChatCommand command, Throwable exception);
}
