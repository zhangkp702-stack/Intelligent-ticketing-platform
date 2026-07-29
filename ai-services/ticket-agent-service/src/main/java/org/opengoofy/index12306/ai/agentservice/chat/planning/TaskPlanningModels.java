package org.opengoofy.index12306.ai.agentservice.chat.planning;

import org.opengoofy.index12306.ai.agentservice.chat.enums.AgentIntent;

import java.util.List;

/**
 * 定义任务规划模型输出及服务端校验后继续使用的结构化数据。
 */
public final class TaskPlanningModels {

    /**
     * 工具类不允许实例化。
     */
    private TaskPlanningModels() {
    }

    /**
     * 当前用户问题拆分后的完整任务计划。
     *
     * @param tasks 按用户表达顺序排列的子任务
     */
    public record TaskPlan(List<PlannedTask> tasks) {
    }

    /**
     * 单个可独立校验和调度的用户任务。
     *
     * @param taskId 当前计划内唯一任务标识
     * @param sequence 用户原始表达中的任务顺序，从 1 开始
     * @param intent 受控业务意图
     * @param originalClause 当前任务对应的用户原文片段
     * @param standaloneQuestion 补全明确上下文后的独立问题
     * @param slots 从用户原话、可信历史或活动工作流提取的业务字段
     * @param missingFields 规划模型判断的缺失字段，服务端会重新计算
     * @param dependsOn 当前任务依赖的其他任务标识
     * @param workflowRelation 当前任务与活动工作流的关系
     * @param unresolvedReferences 仍无法从可信上下文解析的指代表达
     */
    public record PlannedTask(
            String taskId,
            int sequence,
            AgentIntent intent,
            String originalClause,
            String standaloneQuestion,
            TaskSlots slots,
            List<String> missingFields,
            List<String> dependsOn,
            WorkflowRelation workflowRelation,
            List<String> unresolvedReferences) {
    }

    /**
     * 各类任务共用的受控业务字段集合，未涉及的字段必须为空。
     *
     * @param departure 出发站名称
     * @param arrival 到达站名称
     * @param departureDate 出发日期
     * @param trainNumber 车次号
     * @param departureTime 精确出发时间
     * @param seatClass 席别中文名
     * @param selectionPolicy 需要从依赖的查票结果中确定车次时使用的选择策略
     * @param passengerNames 用户明确提供或要求查询的乘车人姓名
     * @param orderSn 订单号
     * @param ridingDate 订单乘车日期
     */
    public record TaskSlots(
            String departure,
            String arrival,
            String departureDate,
            String trainNumber,
            String departureTime,
            String seatClass,
            TrainSelectionPolicy selectionPolicy,
            List<String> passengerNames,
            String orderSn,
            String ridingDate) {
    }

    /**
     * 购票任务从查票结果中确定性选择车次时允许使用的策略。
     */
    public enum TrainSelectionPolicy {
        EARLIEST,
        LATEST,
        CHEAPEST
    }

    /**
     * 当前任务与服务端活动工作流的关系。
     */
    public enum WorkflowRelation {
        INDEPENDENT,
        CONTINUE,
        REPLACE
    }
}
