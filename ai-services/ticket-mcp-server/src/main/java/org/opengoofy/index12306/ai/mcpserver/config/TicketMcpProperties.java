package org.opengoofy.index12306.ai.mcpserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * 票务 MCP 的可信调用边界和下游业务服务配置。
 *
 * @param internalSecret Agent 与 MCP 之间共享的内部签名密钥
 * @param allowedClockSkew 签名时间戳允许的最大偏差
 * @param ticketServiceUrl 票务服务根地址
 * @param userServiceUrl 用户服务根地址
 * @param orderServiceUrl 订单服务根地址
 * @param connectTimeout 下游连接超时
 * @param readTimeout 下游响应读取超时
 * @param stationResultLimit 站点解析最大结果数
 * @param ticketResultLimit 车次查询最大结果数
 * @param passengerResultLimit 乘车人最大结果数
 * @param orderPageSizeLimit 本人订单单页最大结果数
 */
@ConfigurationProperties(prefix = "index12306.agent.mcp")
public record TicketMcpProperties(
        @DefaultValue("") String internalSecret,
        @DefaultValue("2m") Duration allowedClockSkew,
        @DefaultValue("http://127.0.0.1:9002") URI ticketServiceUrl,
        @DefaultValue("http://127.0.0.1:9001") URI userServiceUrl,
        @DefaultValue("http://127.0.0.1:9003") URI orderServiceUrl,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout,
        @DefaultValue("10") int stationResultLimit,
        @DefaultValue("20") int ticketResultLimit,
        @DefaultValue("30") int passengerResultLimit,
        @DefaultValue("20") int orderPageSizeLimit) {
}
