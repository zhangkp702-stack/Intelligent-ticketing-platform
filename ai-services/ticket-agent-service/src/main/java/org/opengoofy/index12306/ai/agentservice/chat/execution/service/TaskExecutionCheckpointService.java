package org.opengoofy.index12306.ai.agentservice.chat.execution.service;

import org.opengoofy.index12306.ai.agentservice.chat.execution.TaskExecutionModels.TaskExecutionResult;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.PlannedTask;
import org.opengoofy.index12306.ai.agentservice.chat.planning.TaskPlanningModels.TaskPlan;
import org.opengoofy.index12306.ai.agentservice.context.AgentRequestContext;

import java.util.Optional;

/**
 * 定义服务端任务计划固化、任务领取和 fenced 终态提交能力。
 */
public interface TaskExecutionCheckpointService {

    /**
     * 加载当前轮次已经固化的服务端任务计划。
     *
     * @param context 当前执行上下文
     * @return 已存在计划；尚未规划时为空
     */
    Optional<TaskPlan> findPlan(AgentRequestContext context);

    /**
     * 将模型临时任务标识重写为服务端标识并原子保存计划。
     *
     * @param context 当前执行上下文
     * @param candidatePlan 已通过确定性校验的模型计划
     * @return 可用于执行和恢复的服务端计划
     */
    TaskPlan persistPlan(AgentRequestContext context, TaskPlan candidatePlan);

    /**
     * 领取单个任务或复用已经持久化的终态结果。
     *
     * @param context 当前执行上下文
     * @param task 当前服务端任务
     * @return 是否需要执行以及可能存在的终态结果
     */
    TaskClaim claim(AgentRequestContext context, PlannedTask task);

    /**
     * 使用当前 Turn fencing token 提交任务终态。
     *
     * @param context 当前执行上下文
     * @param task 当前服务端任务
     * @param result 结构化任务结果
     * @return 已持久化结果
     */
    TaskExecutionResult complete(
            AgentRequestContext context,
            PlannedTask task,
            TaskExecutionResult result);

    /**
     * 在响应式执行被取消时收口当前实例仍持有的任务。
     *
     * @param context 当前执行上下文
     * @param task 当前服务端任务
     */
    void cancel(
            AgentRequestContext context,
            PlannedTask task);

    /**
     * 单次任务领取结果。
     *
     * @param execute 是否需要当前实例实际执行
     * @param existingResult 已有终态结果
     */
    record TaskClaim(
            boolean execute,
            TaskExecutionResult existingResult) {
    }
}
