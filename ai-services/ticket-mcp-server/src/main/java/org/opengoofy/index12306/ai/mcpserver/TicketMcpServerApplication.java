package org.opengoofy.index12306.ai.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 票务能力 MCP 服务启动入口。
 */
@SpringBootApplication
public class TicketMcpServerApplication {

    /**
     * 启动无状态 MCP 服务并初始化 Spring 应用上下文。
     *
     * @param args 应用启动参数
     */
    public static void main(String[] args) {
        // 委托 Spring Boot 创建标准 WebMVC MCP 服务运行环境。
        SpringApplication.run(TicketMcpServerApplication.class, args);
    }
}
