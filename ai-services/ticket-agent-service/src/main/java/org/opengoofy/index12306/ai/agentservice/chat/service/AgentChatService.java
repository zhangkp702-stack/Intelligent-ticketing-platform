package org.opengoofy.index12306.ai.agentservice.chat.service;

import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatCommand;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.ChatEvent;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.PrepareTurnResponse;
import org.opengoofy.index12306.ai.agentservice.chat.model.AgentChatModels.TurnStatusView;
import reactor.core.publisher.Flux;

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
     * 为当前用户预创建尚未执行的服务端轮次。
     *
     * @param userId 用户标识
     * @param conversationId 会话标识
     * @return 轮次标识、提交令牌和截止时间
     */
    PrepareTurnResponse prepareTurn(String userId, String conversationId);

    /**
     * 查询当前用户轮次的持久化状态和最终结果。
     *
     * @param userId 用户标识
     * @param turnId 轮次标识
     * @return 当前轮次安全视图
     */
    TurnStatusView getTurn(String userId, String turnId);

    /**
     * 执行一轮完整对话并返回流式事件。
     *
     * @param command 对话命令
     * @return SSE 接口可消费的事件流
     */
    Flux<ChatEvent> stream(ChatCommand command);

    /**
     * 从客户端最后确认的事件序号继续读取同一轮次，不重复执行模型或业务工具。
     *
     * @param command 原轮次及本次网络尝试信息
     * @param lastEventSequence 客户端最后收到的持久化事件序号，尚未收到时为 0
     * @return 从下一事件开始的可重放 SSE 流
     */
    Flux<ChatEvent> resume(ChatCommand command, long lastEventSequence);

    /**
     * 取消当前用户正在运行的对话轮次。
     *
     * @param userId 用户标识
     * @param turnId 服务端轮次标识
     * @return 找到并取消运行中轮次时返回 true
     */
    boolean cancel(String userId, String turnId);

    /**
     * 将执行异常转换为稳定的前端错误事件。
     *
     * @param command 当前对话命令
     * @param exception 执行异常
     * @return 不暴露内部异常正文的错误事件
     */
    ChatEvent toErrorEvent(ChatCommand command, Throwable exception);
}
