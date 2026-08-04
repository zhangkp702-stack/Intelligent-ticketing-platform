package org.opengoofy.index12306.ai.agentservice.chat.execution;

import org.opengoofy.index12306.ai.agentservice.action.dto.PurchaseActionModels.ActionConfirmationView;
import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;
import org.opengoofy.index12306.ai.agentservice.workflow.dto.WorkflowInteractionView;

import java.util.Comparator;
import java.util.List;

/**
 * 定义多任务执行阶段和最终汇总阶段共享的结构化结果。
 */
public final class TaskExecutionModels {

    /**
     * 工具类不允许实例化。
     */
    private TaskExecutionModels() {
    }

    /**
     * 单个子任务的稳定执行状态。
     */
    public enum TaskExecutionStatus {
        /** 任务尚未满足执行条件。 */
        PENDING,
        /** 任务正在执行。 */
        RUNNING,
        /** 任务已成功完成。 */
        SUCCESS,
        /** 任务需要用户补充信息或作出选择。 */
        NEEDS_INPUT,
        /** 前置依赖未成功，任务未执行。 */
        BLOCKED,
        /** 任务执行超过允许时限。 */
        TIMED_OUT,
        /** 任务执行发生明确失败。 */
        FAILED,
        /** 所属轮次已取消，任务未继续执行。 */
        CANCELLED
    }

    /**
     * 单个任务执行后的隔离结果。
     *
     * @param taskId 当前计划内任务标识
     * @param sequence 用户原始表达顺序
     * @param intent 当前任务意图
     * @param status 稳定执行状态
     * @param question 任务规划器补全后的独立问题
     * @param content 可交给汇总模型的安全结果正文
     * @param missingFields 仍需用户补充的字段
     * @param action 当前交易任务生成的待确认动作
     * @param workflow 当前交易任务等待用户选择的工作流
     */
    public record TaskExecutionResult(
            String taskId,
            int sequence,
            AgentIntent intent,
            TaskExecutionStatus status,
            String question,
            String content,
            List<String> missingFields,
            ActionConfirmationView action,
            WorkflowInteractionView workflow) {

        /**
         * 创建依赖任务未成功时的阻塞结果。
         *
         * @param taskId 当前任务标识
         * @param sequence 当前任务顺序
         * @param intent 当前任务意图
         * @param question 当前独立问题
         * @return 不执行实际业务逻辑的阻塞结果
         */
        public static TaskExecutionResult blocked(
                String taskId,
                int sequence,
                AgentIntent intent,
                String question) {
            // 阻塞原因使用固定服务端文本，不能把上游异常正文传给汇总模型。
            return new TaskExecutionResult(
                    taskId,
                    sequence,
                    intent,
                    TaskExecutionStatus.BLOCKED,
                    question,
                    "前置任务未成功完成，本任务未执行。",
                    List.of(),
                    null,
                    null);
        }

        /**
         * 创建执行器发生异常时的安全失败结果。
         *
         * @param taskId 当前任务标识
         * @param sequence 当前任务顺序
         * @param intent 当前任务意图
         * @param question 当前独立问题
         * @return 不包含内部异常信息的失败结果
         */
        public static TaskExecutionResult failed(
                String taskId,
                int sequence,
                AgentIntent intent,
                String question) {
            // 子任务失败不终止同轮其他查询，最终由汇总回复明确标注失败项。
            return new TaskExecutionResult(
                    taskId,
                    sequence,
                    intent,
                    TaskExecutionStatus.FAILED,
                    question,
                    "该任务执行失败，请稍后重试。",
                    List.of(),
                    null,
                    null);
        }

        /**
         * 创建只读固定链超过执行时限时的安全结果。
         *
         * @param taskId 当前任务标识
         * @param sequence 当前任务顺序
         * @param intent 当前任务意图
         * @param question 当前独立问题
         * @return 不包含底层工具和网络信息的超时结果
         */
        public static TaskExecutionResult timedOut(
                String taskId,
                int sequence,
                AgentIntent intent,
                String question) {
            // 超时只暴露用户可执行的重试建议，不泄漏具体工具、地址或内部调用阶段。
            return new TaskExecutionResult(
                    taskId,
                    sequence,
                    intent,
                    TaskExecutionStatus.TIMED_OUT,
                    question,
                    "该查询执行超时，请稍后重试。",
                    List.of(),
                    null,
                    null);
        }

        /**
         * 创建轮次被取消时尚未完成任务的稳定取消结果。
         *
         * @param taskId 当前任务标识
         * @param sequence 当前任务顺序
         * @param intent 当前任务意图
         * @param question 当前独立问题
         * @return 不包含执行中间数据的取消结果
         */
        public static TaskExecutionResult cancelled(
                String taskId,
                int sequence,
                AgentIntent intent,
                String question) {
            // 取消结果只用于持久化检查点，前端最终状态仍以 Turn 的 CANCELLED 为准。
            return new TaskExecutionResult(
                    taskId,
                    sequence,
                    intent,
                    TaskExecutionStatus.CANCELLED,
                    question,
                    "当前轮次已取消，本任务未继续执行。",
                    List.of(),
                    null,
                    null);
        }
    }

    /**
     * 一轮任务计划全部结束后的不可变结果集合。
     *
     * @param results 按用户原始表达顺序排列的任务结果
     */
    public record TaskExecutionSummary(List<TaskExecutionResult> results) {

        /**
         * 创建按任务顺序排序的不可变汇总。
         *
         * @param results 调度器完成的任务结果
         * @return 可安全交给最终回复生成阶段的汇总
         */
        public static TaskExecutionSummary ordered(List<TaskExecutionResult> results) {
            // 调度完成顺序受并发影响，对外结果必须恢复用户原始表达顺序。
            List<TaskExecutionResult> orderedResults = results.stream()
                    .sorted(Comparator.comparingInt(TaskExecutionResult::sequence))
                    .toList();
            return new TaskExecutionSummary(List.copyOf(orderedResults));
        }

        /**
         * 返回本轮唯一的待确认动作。
         *
         * @return 待确认动作；本轮没有交易草案时为 null
         */
        public ActionConfirmationView action() {
            // 规划校验已限制单轮只有一个交易任务，因此最多存在一个确认动作。
            return results.stream()
                    .map(TaskExecutionResult::action)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        /**
         * 返回本轮唯一的待选择工作流。
         *
         * @return 待选择工作流；当前无需补充选择时为 null
         */
        public WorkflowInteractionView workflow() {
            // 工作流事件与确认动作使用相同的单交易边界。
            return results.stream()
                    .map(TaskExecutionResult::workflow)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
    }
}
