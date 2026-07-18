package org.opengoofy.index12306.ai.agentservice.model.observability;

import org.opengoofy.index12306.ai.agentservice.model.config.ModelRole;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在同一业务请求的 Reactor 链路中关联每一次真实模型 HTTP 调用及其轮次。
 */
public final class ModelHttpCallTraceContext {

    private static final Class<ModelHttpCallTraceContext> CONTEXT_KEY = ModelHttpCallTraceContext.class;

    private final ModelRole role;
    private final ModelAttemptContext attemptContext;
    private final AtomicInteger roundSequence = new AtomicInteger();

    private ModelHttpCallTraceContext(ModelRole role, ModelAttemptContext attemptContext) {
        this.role = role;
        this.attemptContext = attemptContext == null ? ModelAttemptContext.empty() : attemptContext;
    }

    /**
     * 为一次模型路由订阅创建独立的分轮调用上下文。
     *
     * @param role 本次模型调用承担的业务角色
     * @param attemptContext 请求、会话和轮次审计信息
     * @return 尚未分配模型调用轮次的上下文
     */
    public static ModelHttpCallTraceContext create(ModelRole role, ModelAttemptContext attemptContext) {
        // 每次订阅持有独立计数器，避免并发会话之间互相影响分轮序号。
        return new ModelHttpCallTraceContext(role, attemptContext);
    }

    /**
     * 将分轮调用上下文写入当前 Reactor 上下文。
     *
     * @param context 当前 Reactor 上下文
     * @return 包含模型分轮调用信息的新上下文
     */
    public Context writeTo(Context context) {
        // 使用类型作为私有键，避免与其他业务上下文字段发生命名冲突。
        return context.put(CONTEXT_KEY, this);
    }

    /**
     * 从当前 Reactor 链路读取模型分轮调用上下文。
     *
     * @param contextView 当前只读 Reactor 上下文
     * @return 已注册的模型分轮调用上下文，不存在时为空
     */
    public static Optional<ModelHttpCallTraceContext> find(ContextView contextView) {
        // 非在线流式调用可能没有业务关联信息，此时由调用方使用安全占位值记录日志。
        if (!contextView.hasKey(CONTEXT_KEY)) {
            return Optional.empty();
        }
        return Optional.of(contextView.get(CONTEXT_KEY));
    }

    /**
     * 分配当前业务请求内下一个真实模型 HTTP 调用序号。
     *
     * @return 从 1 开始递增的调用轮次
     */
    public int nextRound() {
        // 工具结果再次提交模型时会进入下一轮，序号用于从聚合耗时中区分每次往返。
        return roundSequence.incrementAndGet();
    }

    /**
     * 返回当前模型业务角色。
     *
     * @return 模型角色
     */
    public ModelRole role() {
        return role;
    }

    /**
     * 返回当前请求标识。
     *
     * @return 请求标识，未关联业务请求时为空
     */
    public String requestId() {
        return attemptContext.requestId();
    }

    /**
     * 返回当前会话标识。
     *
     * @return 会话标识，未关联会话时为空
     */
    public String conversationId() {
        return attemptContext.conversationId();
    }

    /**
     * 返回当前对话轮次标识。
     *
     * @return 对话轮次标识，未关联轮次时为空
     */
    public String turnId() {
        return attemptContext.turnId();
    }
}
