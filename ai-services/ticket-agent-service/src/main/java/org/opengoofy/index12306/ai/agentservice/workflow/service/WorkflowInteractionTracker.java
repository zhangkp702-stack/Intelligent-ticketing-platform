package org.opengoofy.index12306.ai.agentservice.workflow.service;

import org.opengoofy.index12306.ai.agentservice.workflow.dto.WorkflowInteractionView;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 暂存当前进程内本轮新生成的结构化工作流交互，数据库工作流仍是权威状态。
 */
@Component
public class WorkflowInteractionTracker {

    private final ConcurrentMap<String, WorkflowInteractionView> interactions = new ConcurrentHashMap<>();

    /**
     * 标记指定轮次需要向前端返回结构化选择视图。
     *
     * @param turnId 当前轮次标识
     * @param interaction 结构化工作流交互视图
     */
    public void markRequired(String turnId, WorkflowInteractionView interaction) {
        // 进程内信号只优化本轮事件输出，页面恢复仍从数据库工作流重建视图。
        interactions.put(
                Objects.requireNonNull(turnId, "turnId"),
                Objects.requireNonNull(interaction, "interaction"));
    }

    /**
     * 原子取得并移除本轮工作流交互视图。
     *
     * @param turnId 当前轮次标识
     * @return 本轮交互视图；不存在时为 null
     */
    public WorkflowInteractionView consume(String turnId) {
        // 移除操作防止完成、异常和取消路径重复返回同一张选择卡片。
        return interactions.remove(Objects.requireNonNull(turnId, "turnId"));
    }
}
