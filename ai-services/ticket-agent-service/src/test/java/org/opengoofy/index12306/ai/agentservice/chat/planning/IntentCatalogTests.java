package org.opengoofy.index12306.ai.agentservice.chat.planning;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证统一意图目录的覆盖范围和关键业务边界。
 */
class IntentCatalogTests {

    private final IntentCatalog catalog = new IntentCatalog();

    /**
     * 验证目录完整且每个受控意图只出现一次。
     */
    @Test
    void catalogCoversEveryIntentExactlyOnce() {
        // 目录必须覆盖服务端枚举，新增意图时不能只修改其中一侧。
        assertThat(catalog.definitions())
                .extracting(IntentDefinition::name)
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(AgentIntent.class))
                .doesNotHaveDuplicates();
    }

    /**
     * 验证取消订单和指定乘车人退票的边界会进入规划提示。
     */
    @Test
    void promptTextContainsCancellationAndRefundBoundary() {
        String promptText = catalog.toPromptText();

        assertThat(promptText)
                .contains("ORDER_CANCELLATION")
                .contains("不允许指定单个乘车人")
                .contains("TICKET_REFUND")
                .contains("只要指定乘车人也属于退票");
    }
}
