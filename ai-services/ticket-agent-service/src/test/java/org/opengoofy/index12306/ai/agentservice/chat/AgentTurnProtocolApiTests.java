package org.opengoofy.index12306.ai.agentservice.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.ConversationEntity;
import org.opengoofy.index12306.ai.agentservice.conversation.service.ConversationMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证服务端轮次预创建、所有权校验和终态查询协议。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AgentTurnProtocolApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    /**
     * 验证前端先获取服务端轮次凭证，再能跨请求查询 DRAFT 和 COMPLETED 状态。
     *
     * @throws Exception HTTP 请求或响应解析失败时抛出
     */
    @Test
    void preparedTurnStatusAndCompletedResultCanBeQueried() throws Exception {
        String userId = unique("protocol-user");
        ConversationEntity conversation = conversationMemoryService.createConversation(
                userId, "轮次接口测试");

        // 预创建接口只返回服务端生成的轮次标识和短期提交令牌。
        MvcResult prepareResult = mockMvc.perform(post(
                        "/api/agent-service/conversations/{conversationId}/turns",
                        conversation.getId())
                        .header("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnId").isNotEmpty())
                .andExpect(jsonPath("$.submissionToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();
        JsonNode prepared = objectMapper.readTree(prepareResult.getResponse().getContentAsByteArray());
        String turnId = prepared.path("turnId").asText();
        String token = prepared.path("submissionToken").asText();

        // 尚未提交的轮次可从任意实例依赖数据库读取为 DRAFT。
        mockMvc.perform(get("/api/agent-service/turns/{turnId}", turnId)
                        .header("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnId").value(turnId))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.content").isEmpty());

        // 模拟流水线完成持久化后，状态接口返回同一个 turnId 的权威最终结果。
        ConversationMemoryService.StartedTurn started = conversationMemoryService.startTurn(
                new ConversationMemoryService.StartTurnCommand(
                        userId, conversation.getId(), turnId, token, "alice", "查询南京到苏州", 6));
        conversationMemoryService.completeTurn(
                new ConversationMemoryService.CompleteTurnCommand(
                        userId, started.turnId(), "已找到可选车次", 6,
                        started.executionOwner(), started.fencingToken()));
        mockMvc.perform(get("/api/agent-service/turns/{turnId}", turnId)
                        .header("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.content").value("已找到可选车次"));
    }

    /**
     * 验证其他认证用户不能查询或使用不属于自己的服务端轮次。
     *
     * @throws Exception HTTP 请求执行失败时抛出
     */
    @Test
    void turnProtocolRejectsCrossUserAccess() throws Exception {
        String ownerId = unique("turn-owner");
        ConversationEntity conversation = conversationMemoryService.createConversation(
                ownerId, "私有轮次测试");
        ConversationMemoryService.PreparedTurn prepared = conversationMemoryService.prepareTurn(
                ownerId, conversation.getId());

        // 轮次状态与会话预创建都必须重新校验认证用户和会话所有权。
        mockMvc.perform(get("/api/agent-service/turns/{turnId}", prepared.turnId())
                        .header("userId", unique("other-user")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failureCategory").value("INVALID_REQUEST"));
        mockMvc.perform(post(
                        "/api/agent-service/conversations/{conversationId}/turns",
                        conversation.getId())
                        .header("userId", unique("other-user")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failureCategory").value("INVALID_REQUEST"));
    }

    /**
     * 验证断线续传游标必须是非负整数，非法值不会退化为一次新的业务执行。
     *
     * @throws Exception HTTP 请求执行失败时抛出
     */
    @Test
    void streamRejectsInvalidLastEventId() throws Exception {
        String userId = unique("cursor-user");
        ConversationEntity conversation = conversationMemoryService.createConversation(
                userId, "续传游标测试");
        ConversationMemoryService.PreparedTurn prepared = conversationMemoryService.prepareTurn(
                userId, conversation.getId());

        // 控制器在订阅流水线前拒绝非法游标，DRAFT 轮次仍保持未执行状态。
        mockMvc.perform(post("/api/agent-service/turns/{turnId}/stream", prepared.turnId())
                        .header("userId", userId)
                        .header("username", "alice")
                        .header("Last-Event-ID", "not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "conversationId", conversation.getId(),
                                "message", "查询车票",
                                "submissionToken", prepared.submissionToken()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failureCategory").value("INVALID_LAST_EVENT_ID"));
    }

    /**
     * 生成满足数据库字段长度约束的唯一测试值。
     *
     * @param prefix 可读前缀
     * @return 唯一文本
     */
    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }
}
