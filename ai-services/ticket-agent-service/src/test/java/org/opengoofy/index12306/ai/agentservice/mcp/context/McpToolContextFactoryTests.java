package org.opengoofy.index12306.ai.agentservice.mcp.context;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Agent 请求身份通过显式工具上下文传递，不依赖 ThreadLocal。
 */
class McpToolContextFactoryTests {

    /**
     * 验证请求、用户、会话、轮次和主题字段完整进入工具上下文且结果不可修改。
     */
    @Test
    void createsImmutableExplicitToolContext() {
        AgentRequestContext requestContext = new AgentRequestContext(
                "request-a", "user-a", "alice", "conversation-a", "turn-a", "topic-a");

        // 工厂直接消费显式业务上下文，不读取执行线程状态。
        Map<String, Object> result = new McpToolContextFactory().create(requestContext);

        assertThat(result)
                .containsEntry("requestId", "request-a")
                .containsEntry("userId", "user-a")
                .containsEntry("conversationId", "conversation-a")
                .containsEntry("turnId", "turn-a")
                .containsEntry("topicId", "topic-a");
        assertThatThrownBy(() -> result.put("userId", "user-b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
