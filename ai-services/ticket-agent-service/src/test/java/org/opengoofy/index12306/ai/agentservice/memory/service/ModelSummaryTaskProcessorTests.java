package org.opengoofy.index12306.ai.agentservice.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageRole;
import org.opengoofy.index12306.ai.agentservice.memory.domain.MessageType;
import org.opengoofy.index12306.ai.agentservice.model.routing.ModelCallResult;
import org.opengoofy.index12306.ai.agentservice.model.structured.StructuredModelInvoker;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证异步摘要处理器将数据库工作项转换为完整摘要结果。
 */
class ModelSummaryTaskProcessorTests {

    /**
     * 验证摘要正文、结构化状态和实际选模元数据会完整返回给任务状态机。
     */
    @Test
    void returnsValidatedSummaryAndSelectedModelMetadata() {
        StructuredModelInvoker invoker = mock(StructuredModelInvoker.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode state = objectMapper.createObjectNode().put("intent", "ticket_query");
        ModelSummaryTaskProcessor.SummaryModelOutput output =
                new ModelSummaryTaskProcessor.SummaryModelOutput(
                        "用户查询北京到上海的余票。",
                        state);
        when(invoker.call(any(), any(), any(), any(), any())).thenReturn(new ModelCallResult<>(
                output,
                "summary-primary",
                "siliconflow",
                "Qwen/Qwen3.5-9B",
                0,
                Duration.ofMillis(30),
                "model-call-1"));
        ModelSummaryTaskProcessor processor = new ModelSummaryTaskProcessor(invoker, objectMapper);
        SummaryTaskService.SummaryWorkItem workItem = new SummaryTaskService.SummaryWorkItem(
                "task-1",
                "conversation-1",
                1L,
                0,
                1L,
                null,
                null,
                List.of(new SummaryTaskService.SummarySourceMessage(
                        "message-1", 1, MessageRole.USER, MessageType.TEXT,
                        "查询北京到上海的余票", 10)));

        // 摘要线程使用工作项恢复上下文，并把选中模型信息交给持久化状态机。
        SummaryTaskService.SummaryGenerationResult result = processor.process(workItem);

        assertThat(result.summaryContent()).isEqualTo("用户查询北京到上海的余票。");
        assertThat(result.structuredState()).isEqualTo("{\"intent\":\"ticket_query\"}");
        assertThat(result.providerId()).isEqualTo("siliconflow");
        assertThat(result.candidateId()).isEqualTo("summary-primary");
    }
}
