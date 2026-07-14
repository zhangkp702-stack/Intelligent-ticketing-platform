package org.opengoofy.index12306.ai.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class TicketMcpServerApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 验证尚未注册业务工具时，无状态 MCP 服务仍可启动并对外提供健康检查。
     *
     * @throws Exception 健康检查请求执行失败时抛出
     */
    @Test
    void healthEndpointIsAvailable() throws Exception {
        // 请求健康检查端点，确认 Web 层、Actuator 和 MCP 自动配置均已初始化。
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
