package org.opengoofy.index12306.ai.agentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 购票智能体编排服务启动入口。
 */
@SpringBootApplication
public class TicketAgentServiceApplication {

    /**
     * 启动购票智能体编排服务并初始化 Spring 应用上下文。
     *
     * @param args 应用启动参数
     */
    public static void main(String[] args) {
        // 委托 Spring Boot 完成组件扫描、自动配置和 Web 服务启动。
        SpringApplication.run(TicketAgentServiceApplication.class, args);
    }
}
