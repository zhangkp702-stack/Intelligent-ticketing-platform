package org.opengoofy.index12306.ai.agentservice.conversation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageType;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.ModelCallResult;
import org.opengoofy.index12306.ai.agentservice.conversation.service.SummaryTaskService;
import org.opengoofy.index12306.ai.agentservice.infra.model.structured.StructuredModelInvoker;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        ObjectNode trip = objectMapper.createObjectNode();
        trip.put("departure", "北京");
        trip.put("arrival", "上海");
        trip.putNull("departureDate");
        trip.putNull("trainNumber");
        trip.putNull("departureTime");
        trip.putNull("seatClass");
        ObjectNode state = objectMapper.createObjectNode();
        state.set("trip", trip);
        state.putArray("passengerNames");
        state.putNull("lastOrderSn");
        state.put("activeIntent", "TRAIN_QUERY");
        state.putNull("pendingRequest");
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
        assertThat(result.structuredState())
                .contains("\"trip\"")
                .contains("\"activeIntent\":\"TRAIN_QUERY\"");
        assertThat(result.providerId()).isEqualTo("siliconflow");
        assertThat(result.candidateId()).isEqualTo("summary-primary");

        // 提示词只包含业务摘要和消息投影，不泄露内部持久化标识或 Token 统计。
        org.mockito.ArgumentCaptor<Prompt> promptCaptor =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(invoker).call(any(), promptCaptor.capture(), any(), any(), any());
        assertThat(promptCaptor.getValue().getInstructions())
                .filteredOn(message -> message instanceof UserMessage)
                .extracting(message -> message.getText())
                .singleElement()
                .satisfies(text -> assertThat(text)
                        .contains("\"sequenceNo\":1")
                        .contains("\"role\":\"USER\"")
                        .doesNotContain("task-1")
                        .doesNotContain("conversation-1")
                        .doesNotContain("message-1")
                        .doesNotContain("tokenCount")
                        .doesNotContain("eventVersion"));
    }
}
