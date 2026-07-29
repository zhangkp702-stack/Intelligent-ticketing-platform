package org.opengoofy.index12306.ai.agentservice.workflow.service;

import org.opengoofy.index12306.ai.agentservice.workflow.dao.entity.AgentWorkflowEntity;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowType;

import java.util.Optional;

/**
 * 定义会话级业务工作流的创建、恢复和阶段推进能力。
 */
public interface AgentWorkflowService {

    /**
     * 启动指定类型的工作流，存在同类型活动工作流时直接恢复。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @param workflowType 工作流类型
     * @param initialStage 初始阶段
     * @param contextJson 初始业务上下文
     * @return 新建或恢复的工作流
     */
    AgentWorkflowEntity startOrResume(
            String userId,
            String conversationId,
            WorkflowType workflowType,
            WorkflowStage initialStage,
            String contextJson);

    /**
     * 查询会话最近的活动工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 会话标识
     * @return 未终止且未过期的工作流
     */
    Optional<AgentWorkflowEntity> findActive(String userId, String conversationId);

    /**
     * 按标识查询当前用户的活动工作流。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @return 未终止且未过期的工作流
     */
    Optional<AgentWorkflowEntity> findActiveById(String userId, String workflowId);

    /**
     * 校验期望阶段并推进到下一阶段。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param expectedStage 期望当前阶段
     * @param nextStage 下一阶段
     * @param contextJson 新业务上下文
     * @return 推进后的工作流
     */
    AgentWorkflowEntity advance(
            String userId,
            String workflowId,
            WorkflowStage expectedStage,
            WorkflowStage nextStage,
            String contextJson);

    /**
     * 校验期望阶段并完成工作流。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param expectedStage 期望当前阶段
     * @param contextJson 最终业务上下文
     * @return 完成后的工作流
     */
    AgentWorkflowEntity complete(
            String userId,
            String workflowId,
            WorkflowStage expectedStage,
            String contextJson);

    /**
     * 在当前阶段不变的情况下更新业务上下文。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @param expectedStage 期望当前阶段
     * @param contextJson 新业务上下文
     * @return 更新后的工作流
     */
    AgentWorkflowEntity updateContext(
            String userId,
            String workflowId,
            WorkflowStage expectedStage,
            String contextJson);

    /**
     * 主动终止指定工作流。
     *
     * @param userId 当前用户标识
     * @param workflowId 工作流标识
     * @return 过期后的工作流
     */
    AgentWorkflowEntity expire(String userId, String workflowId);
}
