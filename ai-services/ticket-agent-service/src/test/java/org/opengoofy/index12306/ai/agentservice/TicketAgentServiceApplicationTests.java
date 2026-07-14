package org.opengoofy.index12306.ai.agentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class TicketAgentServiceApplicationTests {

    @Autowired
    private MockMvc mockMvc;

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
}
