package org.opengoofy.index12306.ai.agentservice.workflow.dto;

import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;

/**
 * 前端需要用户补充选择时使用的通用工作流交互视图。
 */
public interface WorkflowInteractionView {

    /**
     * 返回工作流标识。
     *
     * @return 工作流标识
     */
    String workflowId();

    /**
     * 返回当前工作流阶段。
     *
     * @return 当前阶段
     */
    WorkflowStage stage();

    /**
     * 返回安全的用户提示。
     *
     * @return 交互提示
     */
    String prompt();
}
