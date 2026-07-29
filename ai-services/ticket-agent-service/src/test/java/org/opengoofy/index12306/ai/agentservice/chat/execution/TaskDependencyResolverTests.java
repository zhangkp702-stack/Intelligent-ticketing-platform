package org.opengoofy.index12306.ai.agentservice.chat.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskDependencyResolver.DependencyResolution;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionStatus;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskSlots;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TrainSelectionPolicy;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.WorkflowRelation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证依赖结果解析器只按服务端固定策略选择余票充足的车次。
 */
class TaskDependencyResolverTests {

    private final TaskDependencyResolver resolver =
            new TaskDependencyResolver(new ObjectMapper());

    /**
     * 最早策略必须跳过余票不足的更早车次，并补全唯一车次和时间。
     */
    @Test
    void earliestPolicySelectsFirstTrainWithEnoughSeats() {
        PlannedTask task = purchaseTask(TrainSelectionPolicy.EARLIEST, List.of("张三", "李四"));
        TaskExecutionResult tickets = ticketResult("""
                {
                  "trains": [
                    {
                      "trainId": "train-1",
                      "trainNumber": "G1",
                      "departureTime": "06:00",
                      "seats": [{"type": 1, "quantity": 1, "price": 500}]
                    },
                    {
                      "trainId": "train-2",
                      "trainNumber": "G2",
                      "departureTime": "07:00",
                      "seats": [{"type": 1, "quantity": 2, "price": 550}]
                    },
                    {
                      "trainId": "train-3",
                      "trainNumber": "G3",
                      "departureTime": "08:00",
                      "seats": [{"type": 1, "quantity": 5, "price": 400}]
                    }
                  ]
                }
                """);

        // 两名乘车人要求至少两张一等座，06:00 的单张余票不能被选中。
        DependencyResolution result = resolver.resolve(task, List.of(tickets));

        assertThat(result.resolved()).isTrue();
        assertThat(result.slots().trainNumber()).isEqualTo("G2");
        assertThat(result.slots().departureTime()).isEqualTo("07:00");
        assertThat(result.slots().selectionPolicy()).isNull();
        assertThat(result.selectionSummary()).contains("最早出发").contains("G2");
    }

    /**
     * 最低价策略必须比较指定席别价格，并以出发时间作为稳定次级排序。
     */
    @Test
    void cheapestPolicyUsesSeatPriceAndStableTimeTieBreak() {
        PlannedTask task = purchaseTask(TrainSelectionPolicy.CHEAPEST, List.of("张三"));
        TaskExecutionResult tickets = ticketResult("""
                {
                  "trains": [
                    {
                      "trainId": "train-1",
                      "trainNumber": "G1",
                      "departureTime": "09:00",
                      "seats": [{"type": 1, "quantity": 3, "price": 300.0}]
                    },
                    {
                      "trainId": "train-2",
                      "trainNumber": "G2",
                      "departureTime": "08:00",
                      "seats": [{"type": 1, "quantity": 3, "price": 300.0}]
                    },
                    {
                      "trainId": "train-3",
                      "trainNumber": "G3",
                      "departureTime": "07:00",
                      "seats": [{"type": 1, "quantity": 3, "price": 350.0}]
                    }
                  ]
                }
                """);

        // 相同最低价格下选择更早发车的 G2，结果不依赖接口数组顺序。
        DependencyResolution result = resolver.resolve(task, List.of(tickets));

        assertThat(result.resolved()).isTrue();
        assertThat(result.slots().trainNumber()).isEqualTo("G2");
        assertThat(result.slots().departureTime()).isEqualTo("08:00");
        assertThat(result.selectionSummary()).contains("价格最低");
    }

    /**
     * 创建带指定选择策略的依赖购票任务。
     *
     * @param policy 选车策略
     * @param passengerNames 乘车人姓名
     * @return 可供解析器消费的购票任务
     */
    private PlannedTask purchaseTask(
            TrainSelectionPolicy policy,
            List<String> passengerNames) {
        return new PlannedTask(
                "task-2",
                2,
                AgentIntent.TICKET_PURCHASE,
                "购买最合适的一班",
                "购买查询结果中最合适的一班",
                new TaskSlots(
                        "北京", "上海", "2026-07-30", null, null,
                        "一等座", policy, passengerNames, null, null),
                List.of(),
                List.of("task-1"),
                WorkflowRelation.INDEPENDENT,
                List.of());
    }

    /**
     * 创建成功的查票依赖结果。
     *
     * @param content 查票 JSON
     * @return 固定链成功结果
     */
    private TaskExecutionResult ticketResult(String content) {
        return new TaskExecutionResult(
                "task-1",
                1,
                AgentIntent.TRAIN_QUERY,
                TaskExecutionStatus.SUCCESS,
                "查询车票",
                content,
                List.of(),
                null,
                null);
    }
}
