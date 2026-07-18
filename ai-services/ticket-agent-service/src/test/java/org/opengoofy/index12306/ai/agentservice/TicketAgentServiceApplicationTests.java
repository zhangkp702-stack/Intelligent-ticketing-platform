package org.opengoofy.index12306.ai.agentservice;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.model.client.ModelClientRegistry;
import org.opengoofy.index12306.ai.agentservice.model.config.AgentModelProperties;
import org.opengoofy.index12306.ai.agentservice.model.config.ModelRole;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class TicketAgentServiceApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentModelProperties modelProperties;

    @Autowired
    private ModelClientRegistry modelClientRegistry;

    /**
     * 验证未配置真实模型和 MCP 服务时，智能体服务仍可启动并对外提供健康检查。
     *
     * @throws Exception 健康检查请求执行失败时抛出
     */
    @Test
    void healthEndpointIsAvailable() throws Exception {
        // 请求健康检查端点，确认 Web 层和 Actuator 均已完成初始化。
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    /**
     * 验证多平台候选模型和各角色降级链能够从 YAML 完整绑定。
     */
    @Test
    void modelRoutingConfigurationIsBound() {
        // 检查回答链和摘要链的首选模型，防止枚举键或模型 ID 绑定错误。
        assertThat(modelProperties.routes().get(ModelRole.ANSWER_TOOL))
                .startsWith("bailian-answer-primary", "siliconflow-answer-secondary");
        assertThat(modelProperties.routes().get(ModelRole.MEMORY_SUMMARY))
                .startsWith("siliconflow-summary-primary", "bailian-flash");
        assertThat(modelProperties.candidates().get("bailian-answer-primary").model())
                .isEqualTo("qwen3.5-plus-2026-02-15");

        // 百炼混合思考模型必须显式关闭思考，避免默认推理过程重复放大工具循环耗时。
        assertThat(modelProperties.candidates().get("bailian-answer-primary").extraBody())
                .containsEntry("enable_thinking", false);
        assertThat(modelProperties.candidates().get("bailian-flash").extraBody())
                .containsEntry("enable_thinking", false);
    }

    /**
     * 验证缺少外部 API Key 时不会创建可发起网络请求的模型客户端。
     */
    @Test
    void noModelClientIsRegisteredWithoutApiKey() {
        // 测试配置不提供密钥，服务应安全启动且客户端注册表保持为空。
        assertThat(modelClientRegistry.all()).isEmpty();
    }

    /**
     * 验证网关注入用户身份后可以创建独立智能体会话。
     *
     * @throws Exception 请求执行失败时抛出
     */
    @Test
    void authenticatedUserCanCreateConversation() throws Exception {
        // 请求体只允许提供标题，用户身份必须来自网关认证头。
        mockMvc.perform(post("/api/agent-service/conversations")
                        .header("userId", "user-api-test")
                        .contentType("application/json")
                        .content("{\"title\":\"购票咨询\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").isNotEmpty());
    }
}
