package org.opengoofy.index12306.ai.agentservice.workflow.dto;

import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowType;

import java.util.Map;

/**
 * 提供给任务规划模型的最小活动工作流上下文。
 *
 * @param workflowType 当前工作流类型
 * @param stage 当前服务端阶段
 * @param verifiedFacts 允许用于补全指代的服务端已确认业务事实
 * @param awaitingUserSelection 是否必须等待用户通过表单提交选择
 */
public record WorkflowPlanningContext(
        WorkflowType workflowType,
        WorkflowStage stage,
        Map<String, Object> verifiedFacts,
        boolean awaitingUserSelection) {

    /**
     * 创建不可变的最小规划上下文，避免后续调用方追加内部标识或候选列表。
     */
    public WorkflowPlanningContext {
        // 工作流事实只允许从服务端构造的白名单映射进入规划模型。
        verifiedFacts = verifiedFacts == null ? Map.of() : Map.copyOf(verifiedFacts);
    }
}
