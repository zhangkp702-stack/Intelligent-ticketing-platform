package org.opengoofy.index12306.ai.agentservice.action.config;

import org.opengoofy.index12306.ai.agentservice.action.PurchaseDraftTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把安全的购票草案方法注册为回答模型可见的 Spring AI 工具。
 */
@Configuration(proxyBeanMethods = false)
public class PurchaseDraftToolConfiguration {

    /**
     * 注册只创建草案、不执行真实写操作的本地工具提供器。
     *
     * @param tools 购票草案工具对象
     * @return Spring AI 工具提供器
     */
    @Bean
    public ToolCallbackProvider purchaseDraftToolCallbacks(PurchaseDraftTools tools) {
        // 真实 MCP 写工具不放入该提供器，因此回答模型无法发现或调用下单能力。
        return ToolCallbackProvider.from(ToolCallbacks.from(tools));
    }
}
