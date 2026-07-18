package org.opengoofy.index12306.ai.agentservice.chat;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchasePassenger;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionModels.PurchasePayload;
import org.opengoofy.index12306.ai.agentservice.action.PurchaseActionService;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;
import org.opengoofy.index12306.ai.agentservice.memory.domain.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.memory.service.ConversationMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AgentConversationHistoryApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private PurchaseActionService purchaseActionService;

    /**
     * 验证会话列表、消息游标和待确认操作可以从持久化状态恢复。
     *
     * @throws Exception HTTP 请求执行失败时抛出
     */
    @Test
    void conversationHistoryAndPendingActionCanBeRecovered() throws Exception {
        String userId = unique("history-user");
        Fixture fixture = createConversationFixture(userId);

        // 会话列表只返回当前用户数据，并携带前端恢复所需的活动主题和消息边界。
        mockMvc.perform(get("/api/agent-service/conversations")
                        .header("userId", userId)
                        .param("current", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].conversationId")
                        .value(fixture.conversationId()))
                .andExpect(jsonPath("$.records[0].title").value("历史恢复测试"))
                .andExpect(jsonPath("$.records[0].lastMessageSequence").value(2));

        // 首次只取最新一条消息，服务端返回下一页游标而不是数据库页码。
        mockMvc.perform(get("/api/agent-service/conversations/{conversationId}/messages",
                        fixture.conversationId())
                        .header("userId", userId)
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].sequenceNo").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.messages[0].content").value("已为你生成购票确认卡片"))
                .andExpect(jsonPath("$.nextBeforeSequence").value(2))
                .andExpect(jsonPath("$.hasMore").value(true));

        // 使用上一页返回的序号继续向前读取，历史消息仍按时间正序交付前端。
        mockMvc.perform(get("/api/agent-service/conversations/{conversationId}/messages",
                        fixture.conversationId())
                        .header("userId", userId)
                        .param("beforeSequence", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].sequenceNo").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[0].content").value("帮我购买测试车票"))
                .andExpect(jsonPath("$.hasMore").value(false));

        // 页面刷新后从数据库恢复操作摘要，只有待确认状态会重新获得确认令牌。
        mockMvc.perform(get("/api/agent-service/conversations/{conversationId}/pending-action",
                        fixture.conversationId())
                        .header("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnId").value(fixture.turnId()))
                .andExpect(jsonPath("$.action.actionId").value(fixture.actionId()))
                .andExpect(jsonPath("$.action.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.action.confirmationToken").isNotEmpty())
                .andExpect(jsonPath("$.execution.status").value("AWAITING_CONFIRMATION"));
    }

    /**
     * 验证其他用户无法读取会话消息或恢复其中的操作。
     *
     * @throws Exception HTTP 请求执行失败时抛出
     */
    @Test
    void conversationHistoryRejectsCrossUserAccess() throws Exception {
        Fixture fixture = createConversationFixture(unique("owner"));
        String otherUserId = unique("other-user");

        // 消息和操作恢复共用服务端会话归属校验，不能依赖前端隐藏会话标识。
        mockMvc.perform(get("/api/agent-service/conversations/{conversationId}/messages",
                        fixture.conversationId())
                        .header("userId", otherUserId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failureCategory").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/agent-service/conversations/{conversationId}/pending-action",
                        fixture.conversationId())
                        .header("userId", otherUserId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failureCategory").value("INVALID_REQUEST"));
    }

    /**
     * 创建包含一轮完整消息和待确认购票草案的测试会话。
     *
     * @param userId 会话所有者
     * @return 测试会话标识集合
     */
    private Fixture createConversationFixture(String userId) {
        String requestId = unique("request");
        ConversationEntity conversation = conversationMemoryService.createConversation(
                userId, "历史恢复测试");
        // 草案只能绑定运行中的可信轮次，因此先写入用户问题。
        ConversationMemoryService.StartedTurn turn = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId,
                        conversation.getId(),
                        requestId,
                        requestId,
                        "帮我购买测试车票",
                        8));
        AgentRequestContext context = new AgentRequestContext(
                requestId,
                userId,
                "history-test-user",
                conversation.getId(),
                turn.turnId());

        // 创建待确认草案但不调用真实 MCP，保证测试只验证本地恢复和安全边界。
        String actionId = purchaseActionService.prepare(
                context,
                new PurchasePayload(
                        "train-history-100",
                        "北京南",
                        "上海虹桥",
                        "2099-01-01",
                        List.of(new PurchasePassenger("passenger-history-1", 3)),
                        List.of("01A")))
                .actionId();
        conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        userId,
                        turn.turnId(),
                        "已为你生成购票确认卡片",
                        10));
        return new Fixture(
                conversation.getId(), turn.turnId(), actionId);
    }

    /**
     * 生成满足数据库字段长度限制的唯一测试值。
     *
     * @param prefix 可读前缀
     * @return 唯一文本
     */
    private String unique(String prefix) {
        // 去除 UUID 分隔符，避免测试标识包含无业务意义的特殊字符。
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param actionId 操作草案标识
     */
    private record Fixture(
            String conversationId,
            String turnId,
            String actionId) {
    }
}
