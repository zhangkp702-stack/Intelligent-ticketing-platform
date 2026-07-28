package org.opengoofy.index12306.ai.mcpserver.tool;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.OrderOperationPreview;
import org.opengoofy.index12306.ai.mcpserver.tool.TicketToolResult.RefundPreview;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证票务 MCP 结构化输出的数据契约。
 */
class TicketToolResultTests {

    /**
     * 验证下游未返回订单操作原因时，MCP 输出仍提供字符串。
     */
    @Test
    void normalizesMissingOrderOperationReason() {
        // 可执行操作没有失败原因，输出层应将 null 转换为空字符串。
        OrderOperationPreview preview = new OrderOperationPreview(
                "order-1", 0, true, false, false, null);

        assertThat(preview.reason()).isEmpty();
    }

    /**
     * 验证下游未返回退票原因时，MCP 输出仍提供字符串。
     */
    @Test
    void normalizesMissingRefundReason() {
        // 允许退票时没有失败原因，输出层应将 null 转换为空字符串。
        RefundPreview preview = new RefundPreview(
                "order-1", 1, true, 55300, List.of(), null);

        assertThat(preview.reason()).isEmpty();
    }
}
