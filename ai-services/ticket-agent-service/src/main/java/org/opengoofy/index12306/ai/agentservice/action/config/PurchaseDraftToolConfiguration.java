package org.opengoofy.index12306.ai.agentservice.action.config;

import org.opengoofy.index12306.ai.agentservice.action.PurchaseDraftTools;
import org.opengoofy.index12306.ai.agentservice.action.TicketOperationDraftTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把安全的购票、取消和退票草案方法注册为回答模型可见的 Spring AI 工具。
 */
@Configuration(proxyBeanMethods = false)
public class PurchaseDraftToolConfiguration {

    /**
     * 注册只创建草案、不执行真实写操作的本地工具提供器。
     *
     * @param purchaseTools 购票草案工具对象
     * @param ticketOperationTools 取消和退票草案工具对象
     * @return Spring AI 工具提供器
     */
    @Bean
    public ToolCallbackProvider purchaseDraftToolCallbacks(
            PurchaseDraftTools purchaseTools,
            TicketOperationDraftTools ticketOperationTools) {
        // 三类真实 MCP 写工具都不放入该提供器，回答模型只能创建待确认草案。
        return ToolCallbackProvider.from(ToolCallbacks.from(purchaseTools, ticketOperationTools));
    }
}
