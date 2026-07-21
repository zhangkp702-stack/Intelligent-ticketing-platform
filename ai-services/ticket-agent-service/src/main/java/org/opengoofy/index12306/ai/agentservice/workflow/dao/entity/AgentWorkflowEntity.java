package org.opengoofy.index12306.ai.agentservice.workflow.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowStage;
import org.opengoofy.index12306.ai.agentservice.workflow.enums.WorkflowType;
import org.opengoofy.index12306.ai.agentservice.conversation.dao.entity.AgentBaseEntity;

import java.time.Instant;

/**
 * 保存由服务端负责推进的会话业务工作流，禁止使用模型摘要承载可执行状态。
 */
@Getter
@Entity
@Table(name = "t_agent_workflow")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentWorkflowEntity extends AgentBaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false, length = 64)
    private String userId;

    @Column(name = "conversation_id", nullable = false, updatable = false, length = 32)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_type", nullable = false, updatable = false, length = 32)
    private WorkflowType workflowType;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 64)
    private WorkflowStage stage;

    @Column(name = "context_json", nullable = false, columnDefinition = "TEXT")
    private String contextJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * 创建一个尚未完成的工作流。
     *
     * @param userId 当前用户标识
     * @param conversationId 所属会话标识
     * @param workflowType 工作流类型
     * @param stage 初始阶段
     * @param contextJson 不含敏感信息的服务端上下文
     * @param expiresAt 工作流过期时间
     * @param now 创建时间
     * @return 新建工作流实体
     */
    public static AgentWorkflowEntity create(String userId, String conversationId, WorkflowType workflowType,
                                             WorkflowStage stage, String contextJson, Instant expiresAt, Instant now) {
        // 工作流只保存候选 ID 和用户已选择的业务字段，证件号码等敏感信息不进入上下文。
        return new AgentWorkflowEntity(now, userId, conversationId, workflowType, stage, contextJson, expiresAt);
    }

    /**
     * 推进工作流阶段并替换由服务端验证后的上下文。
     *
     * @param stage 下一阶段
     * @param contextJson 新上下文
     * @param now 修改时间
     */
    public void advance(WorkflowStage stage, String contextJson, Instant now) {
        // 阶段和上下文同一实体更新，使前端提交选择后能够以乐观锁防止并发重复推进。
        this.stage = stage;
        this.contextJson = contextJson;
        touch(now);
    }

    /**
     * 将尚未完成的工作流标记为过期，并保留原有脱敏上下文供审计使用。
     *
     * @param now 过期时间
     */
    public void expire(Instant now) {
        // 过期只终止后续推进，不删除已收集且通过服务端校验的业务上下文。
        this.stage = WorkflowStage.EXPIRED;
        touch(now);
    }

    private AgentWorkflowEntity(Instant now, String userId, String conversationId, WorkflowType workflowType,
                                WorkflowStage stage, String contextJson, Instant expiresAt) {
        super(now);
        this.userId = userId;
        this.conversationId = conversationId;
        this.workflowType = workflowType;
        this.stage = stage;
        this.contextJson = contextJson;
        this.expiresAt = expiresAt;
    }

}
