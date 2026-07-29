package org.opengoofy.index12306.ai.agentservice.chat.planning;

import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskPlan;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskSlots;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TrainSelectionPolicy;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.WorkflowRelation;
import org.opengoofy.index12306.ai.agentservice.infra.model.structured.InvalidModelOutputException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证任务计划的确定性边界、缺失字段重算和依赖图检查。
 */
class TaskPlanValidatorTests {

    private final TaskPlanValidator validator = new TaskPlanValidator();

    /**
     * 验证三个任务会按顺序规范化，并由服务端重新计算两个日期缺失项。
     */
    @Test
    void validCompoundPlanRecalculatesMissingFields() {
        TaskSlots trainQuerySlots =
                slots("北京", "南京", null, null, null, null, List.of(), null, null);
        TaskSlots purchaseSlots =
                slots("上海", "北京", null, null, null, "一等座", List.of(" 万重山 ", "万重山"), null, null);
        TaskPlan source = new TaskPlan(List.of(
                task("task-3", 3, AgentIntent.PASSENGER_QUERY, emptySlots(), List.of()),
                task("task-1", 1, AgentIntent.TRAIN_QUERY, trainQuerySlots, List.of()),
                task("task-2", 2, AgentIntent.TICKET_PURCHASE, purchaseSlots, List.of())));

        // 模型给出的缺失字段不作为权威结果，校验器根据不同意图所需槽位重新计算。
        TaskPlan result = validator.validate(source);

        assertThat(result.tasks())
                .extracting(PlannedTask::taskId)
                .containsExactly("task-1", "task-2", "task-3");
        assertThat(result.tasks().get(0).missingFields()).containsExactly("departureDate");
        assertThat(result.tasks().get(1).missingFields()).containsExactly("departureDate");
        assertThat(result.tasks().get(1).slots().passengerNames()).containsExactly("万重山");
        assertThat(result.tasks().get(2).missingFields()).isEmpty();
    }

    /**
     * 验证单轮任务数量超过五个时被拒绝。
     */
    @Test
    void rejectsTooManyTasks() {
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.GENERAL_CHAT, emptySlots(), List.of()),
                task("task-2", 2, AgentIntent.GENERAL_CHAT, emptySlots(), List.of()),
                task("task-3", 3, AgentIntent.GENERAL_CHAT, emptySlots(), List.of()),
                task("task-4", 4, AgentIntent.GENERAL_CHAT, emptySlots(), List.of()),
                task("task-5", 5, AgentIntent.GENERAL_CHAT, emptySlots(), List.of()),
                task("task-6", 6, AgentIntent.GENERAL_CHAT, emptySlots(), List.of())));

        assertThatThrownBy(() -> validator.validate(source))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("任务数量不能超过 5");
    }

    /**
     * 验证重复任务标识不能进入后续任务调度。
     */
    @Test
    void rejectsDuplicateTaskIds() {
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.GENERAL_CHAT, emptySlots(), List.of()),
                task("task-1", 2, AgentIntent.PASSENGER_QUERY, emptySlots(), List.of())));

        assertThatThrownBy(() -> validator.validate(source))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("任务标识不能重复");
    }

    /**
     * 验证依赖不存在的任务标识时被拒绝。
     */
    @Test
    void rejectsUnknownDependency() {
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.PASSENGER_QUERY, emptySlots(), List.of("task-9"))));

        assertThatThrownBy(() -> validator.validate(source))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("任务依赖不存在");
    }

    /**
     * 验证两个任务形成循环依赖时被拒绝。
     */
    @Test
    void rejectsCyclicDependencies() {
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.TRAIN_QUERY, completeTrainQuerySlots(), List.of("task-2")),
                task("task-2", 2, AgentIntent.PASSENGER_QUERY, emptySlots(), List.of("task-1"))));

        assertThatThrownBy(() -> validator.validate(source))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("任务依赖不能形成循环");
    }

    /**
     * 验证单轮包含多个交易任务时被拒绝。
     */
    @Test
    void rejectsMultipleTransactionTasks() {
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.TICKET_PURCHASE, emptySlots(), List.of()),
                task("task-2", 2, AgentIntent.TICKET_REFUND, emptySlots(), List.of())));

        assertThatThrownBy(() -> validator.validate(source))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("最多只能包含一个交易任务");
    }

    /**
     * 验证交易任务不能成为后续查询依赖，避免读取尚待用户确认的写结果。
     */
    @Test
    void rejectsTransactionAsDependency() {
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.TICKET_PURCHASE, emptySlots(), List.of()),
                task("task-2", 2, AgentIntent.ORDER_QUERY, emptySlots(), List.of("task-1"))));

        assertThatThrownBy(() -> validator.validate(source))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("交易任务不能成为其他任务的依赖");
    }

    /**
     * 验证支付状态查询必须由服务端重新计算订单号缺失项。
     */
    @Test
    void paymentQueryRequiresOrderNumber() {
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.PAYMENT_QUERY, emptySlots(), List.of())));

        TaskPlan result = validator.validate(source);

        assertThat(result.tasks().get(0).missingFields()).containsExactly("orderSn");
    }

    /**
     * 验证指定乘车人的取消表达会被服务端校正为退票。
     */
    @Test
    void cancellationWithPassengerIsNormalizedToRefund() {
        TaskSlots cancellationSlots =
                slots(null, null, null, "G9001", null, null, List.of("万重山"), "order-1", "2026-07-30");
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.ORDER_CANCELLATION, cancellationSlots, List.of())));

        // 即使规划模型误选整单取消，服务端也不能把指定乘车人的请求交给整单取消链。
        TaskPlan result = validator.validate(source);

        assertThat(result.tasks().get(0).intent()).isEqualTo(AgentIntent.TICKET_REFUND);
        assertThat(result.tasks().get(0).slots().passengerNames()).containsExactly("万重山");
        assertThat(result.tasks().get(0).slots().orderSn()).isEqualTo("order-1");
    }

    /**
     * 验证未指定乘车人的取消表达仍保持整单取消意图。
     */
    @Test
    void cancellationWithoutPassengerRemainsCancellation() {
        TaskSlots cancellationSlots =
                slots(null, null, null, "G9001", null, null, List.of(), "order-1", "2026-07-30");
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.ORDER_CANCELLATION, cancellationSlots, List.of())));

        TaskPlan result = validator.validate(source);

        assertThat(result.tasks().get(0).intent()).isEqualTo(AgentIntent.ORDER_CANCELLATION);
    }

    /**
     * 验证乘车人查询中混入其他行程字段时被拒绝。
     */
    @Test
    void rejectsSlotsFromAnotherTaskIntent() {
        TaskSlots invalidSlots =
                slots("北京", null, null, null, null, null, List.of(), null, null);
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.PASSENGER_QUERY, invalidSlots, List.of())));

        assertThatThrownBy(() -> validator.validate(source))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("与意图无关的业务字段");
    }

    /**
     * 验证购票选车策略必须依赖真实查票任务。
     */
    @Test
    void purchaseSelectionPolicyRequiresTrainQueryDependency() {
        TaskSlots purchaseSlots = new TaskSlots(
                "北京", "上海", "2026-07-30", null, null,
                "一等座", TrainSelectionPolicy.EARLIEST,
                List.of("万重山"), null, null);
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.PASSENGER_QUERY, emptySlots(), List.of()),
                task("task-2", 2, AgentIntent.TICKET_PURCHASE, purchaseSlots, List.of("task-1"))));

        assertThatThrownBy(() -> validator.validate(source))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("必须依赖查票任务");
    }

    /**
     * 验证合法选车策略和查票依赖可以进入确定性调度。
     */
    @Test
    void acceptsSelectionPolicyWithTrainQueryDependency() {
        TaskSlots purchaseSlots = new TaskSlots(
                "北京", "上海", "2026-07-30", null, null,
                "一等座", TrainSelectionPolicy.CHEAPEST,
                List.of("万重山"), null, null);
        TaskPlan source = new TaskPlan(List.of(
                task("task-1", 1, AgentIntent.TRAIN_QUERY, completeTrainQuerySlots(), List.of()),
                task("task-2", 2, AgentIntent.TICKET_PURCHASE, purchaseSlots, List.of("task-1"))));

        TaskPlan result = validator.validate(source);

        assertThat(result.tasks().get(1).slots().selectionPolicy())
                .isEqualTo(TrainSelectionPolicy.CHEAPEST);
    }

    /**
     * 创建通用规划任务，并故意提供错误的模型缺失字段供服务端覆盖。
     *
     * @param taskId 任务标识
     * @param sequence 任务顺序
     * @param intent 业务意图
     * @param slots 业务槽位
     * @param dependsOn 依赖任务标识
     * @return 待校验子任务
     */
    private PlannedTask task(
            String taskId,
            int sequence,
            AgentIntent intent,
            TaskSlots slots,
            List<String> dependsOn) {
        return new PlannedTask(
                taskId,
                sequence,
                intent,
                "原始问题-" + taskId,
                "独立问题-" + taskId,
                slots,
                List.of("model-guessed-field"),
                dependsOn,
                WorkflowRelation.INDEPENDENT,
                List.of());
    }

    /**
     * 创建全部为空的业务槽位。
     *
     * @return 不携带任何业务字段的槽位
     */
    private TaskSlots emptySlots() {
        return slots(null, null, null, null, null, null, List.of(), null, null);
    }

    /**
     * 创建字段完整的查票槽位。
     *
     * @return 可直接执行北京到南京查票的槽位
     */
    private TaskSlots completeTrainQuerySlots() {
        return slots("北京", "南京", "2026-07-30", null, null, null, List.of(), null, null);
    }

    /**
     * 创建统一业务槽位对象。
     *
     * @param departure 出发站
     * @param arrival 到达站
     * @param departureDate 出发日期
     * @param trainNumber 车次号
     * @param departureTime 出发时间
     * @param seatClass 席别
     * @param passengerNames 乘车人姓名
     * @param orderSn 订单号
     * @param ridingDate 订单乘车日期
     * @return 测试业务槽位
     */
    private TaskSlots slots(
            String departure,
            String arrival,
            String departureDate,
            String trainNumber,
            String departureTime,
            String seatClass,
            List<String> passengerNames,
            String orderSn,
            String ridingDate) {
        return new TaskSlots(
                departure,
                arrival,
                departureDate,
                trainNumber,
                departureTime,
                seatClass,
                null,
                passengerNames,
                orderSn,
                ridingDate);
    }
}
