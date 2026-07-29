package org.opengoofy.index12306.ai.agentservice.chat.planning;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskPlan;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskSlots;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.WorkflowRelation;
import org.opengoofy.index12306.ai.agentservice.conversation.context.AgentChatMessage;
import org.opengoofy.index12306.ai.agentservice.conversation.context.ConversationHistoryContext;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelRole;
import org.opengoofy.index12306.ai.agentservice.infra.model.observability.ModelAttemptContext;
import org.opengoofy.index12306.ai.agentservice.infra.model.routing.ModelCallResult;
import org.opengoofy.index12306.ai.agentservice.infra.model.structured.StructuredModelInvoker;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证任务规划模型的一次调用边界、上下文组装和结构化返回值。
 */
class TaskPlanningServiceTests {

    /**
     * 验证复合请求通过一次结构化模型调用返回三个有序任务。
     */
    @Test
    void compoundRequestUsesSingleStructuredPlanningCall() {
        StructuredModelInvoker invoker = mock(StructuredModelInvoker.class);
        TaskPlanValidator validator = new TaskPlanValidator();
        Clock clock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC);
        TaskPlanningService service = new TaskPlanningService(
                invoker, validator, new IntentCatalog(), clock);
        TaskPlan taskPlan = threeTaskPlan();
        when(invoker.call(
                eq(ModelRole.INTENT_CLASSIFICATION),
                any(),
                any(),
                eq(TaskPlan.class),
                any()))
                .thenReturn(new ModelCallResult<>(
                        taskPlan,
                        "bailian-flash",
                        "bailian",
                        "qwen3.5-flash-2026-02-23",
                        0,
                        Duration.ofMillis(30),
                        "model-call-1"));

        // 复合请求只进入一次规划模型，返回值已经同时包含拆分任务和独立问题。
        TaskPlan result = service.plan(
                history("查询北京到南京的高铁，给万重山买上海到北京的一等座，再查乘车人"),
                "当前购票工作流由服务端维护，stage=SELECTING_PASSENGERS",
                new ModelAttemptContext("request-1", "conversation-1", "turn-1"));

        assertThat(result.tasks())
                .extracting(PlannedTask::intent)
                .containsExactly(
                        AgentIntent.TRAIN_QUERY,
                        AgentIntent.TICKET_PURCHASE,
                        AgentIntent.PASSENGER_QUERY);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(invoker, times(1)).call(
                eq(ModelRole.INTENT_CLASSIFICATION),
                promptCaptor.capture(),
                any(),
                eq(TaskPlan.class),
                any());
        Prompt prompt = promptCaptor.getValue();
        assertThat(prompt.getInstructions().get(0))
                .isInstanceOfSatisfying(SystemMessage.class, message -> {
                    assertThat(message.getText()).contains("任务规划器");
                    assertThat(message.getText()).contains("当前日期：2026-07-29");
                    assertThat(message.getText()).contains("selectionPolicy");
                    assertThat(message.getText()).contains("EARLIEST、LATEST 或 CHEAPEST");
                    assertThat(message.getText()).contains(
                            "ORDER_CANCELLATION：取消尚未支付的整个订单");
                    assertThat(message.getText()).contains(
                            "TICKET_REFUND：对已购车票发起全部或部分退票");
                });
        assertThat(prompt.getInstructions())
                .anySatisfy(message -> assertThat(message.getText())
                        .contains("stage=SELECTING_PASSENGERS"));
        assertThat(prompt.getInstructions().get(prompt.getInstructions().size() - 1))
                .isInstanceOfSatisfying(UserMessage.class, message ->
                        assertThat(message.getText()).isEqualTo(
                                "查询北京到南京的高铁，给万重山买上海到北京的一等座，再查乘车人"));
    }

    /**
     * 创建包含三个独立业务任务的模型返回结果。
     *
     * @return 查票、购票和乘车人查询任务计划
     */
    private TaskPlan threeTaskPlan() {
        TaskSlots trainQuerySlots =
                new TaskSlots("北京", "南京", null, null, null, null, null, List.of(), null, null);
        TaskSlots purchaseSlots = new TaskSlots(
                "上海", "北京", null, null, null, "一等座", null, List.of("万重山"), null, null);
        TaskSlots passengerSlots =
                new TaskSlots(null, null, null, null, null, null, null, List.of(), null, null);
        return new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.TRAIN_QUERY, "查询北京到南京的高铁", trainQuerySlots),
                task("task-2", 2, AgentIntent.TICKET_PURCHASE, "给万重山买上海到北京的一等座", purchaseSlots),
                task("task-3", 3, AgentIntent.PASSENGER_QUERY, "查询当前乘车人", passengerSlots)));
    }

    /**
     * 创建单个结构化规划任务。
     *
     * @param taskId 任务标识
     * @param sequence 任务顺序
     * @param intent 业务意图
     * @param question 原文及独立问题
     * @param slots 业务槽位
     * @return 可用于规划模型返回值的子任务
     */
    private PlannedTask task(
            String taskId,
            int sequence,
            AgentIntent intent,
            String question,
            TaskSlots slots) {
        return new PlannedTask(
                taskId,
                sequence,
                intent,
                question,
                question,
                slots,
                List.of(),
                List.of(),
                WorkflowRelation.INDEPENDENT,
                List.of());
    }

    /**
     * 创建只包含当前用户问题的规划上下文。
     *
     * @param question 当前复合问题
     * @return 最小会话历史上下文
     */
    private ConversationHistoryContext history(String question) {
        // 当前测试不依赖旧轮次，只验证原始复合问题保持为最后一条用户消息。
        return new ConversationHistoryContext(
                "conversation-1",
                null,
                null,
                null,
                null,
                0,
                List.of(),
                AgentChatMessage.user(question),
                List.of(),
                null,
                null,
                20);
    }
}
