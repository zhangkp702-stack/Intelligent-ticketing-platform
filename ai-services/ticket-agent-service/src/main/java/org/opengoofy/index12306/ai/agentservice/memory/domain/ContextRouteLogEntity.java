package org.opengoofy.index12306.ai.agentservice.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 当前问题从主题候选集合中选择目标主题的审计记录。
 */
@Getter
@Entity
@Table(name = "t_agent_context_route_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContextRouteLogEntity extends AgentBaseEntity {

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "conversation_id", nullable = false, length = 32)
    private String conversationId;

    @Column(name = "current_message_id", nullable = false, length = 32)
    private String currentMessageId;

    @Column(name = "candidate_topic_ids", nullable = false, columnDefinition = "TEXT")
    private String candidateTopicIds;

    @Column(name = "selected_topic_id", length = 32)
    private String selectedTopicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 32)
    private RouteDecision decision;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "model_call_id", length = 32)
    private String modelCallId;

    private ContextRouteLogEntity(
            String requestId,
            String conversationId,
            String currentMessageId,
            String candidateTopicIds,
            String selectedTopicId,
            RouteDecision decision,
            BigDecimal confidence,
            String modelCallId,
            Instant now) {
        super(now);
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.currentMessageId = Objects.requireNonNull(currentMessageId, "currentMessageId");
        this.candidateTopicIds = Objects.requireNonNull(candidateTopicIds, "candidateTopicIds");
        this.selectedTopicId = selectedTopicId;
        this.decision = Objects.requireNonNull(decision, "decision");
        this.confidence = confidence;
        this.modelCallId = modelCallId;
    }

    /**
     * 创建主题选择结果审计记录。
     *
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param currentMessageId 当前用户消息标识
     * @param candidateTopicIds 候选主题标识 JSON
     * @param selectedTopicId 选中主题标识，新主题决策时允许为空
     * @param decision 路由决策
     * @param confidence 模型置信度
     * @param modelCallId 对应模型调用标识
     * @param now 创建时间
     * @return 路由审计实体
     */
    public static ContextRouteLogEntity create(
            String requestId,
            String conversationId,
            String currentMessageId,
            String candidateTopicIds,
            String selectedTopicId,
            RouteDecision decision,
            BigDecimal confidence,
            String modelCallId,
            Instant now) {
        // 候选主题仅保存标识列表，不复制摘要卡片和用户问题正文。
        return new ContextRouteLogEntity(
                requestId, conversationId, currentMessageId, candidateTopicIds,
                selectedTopicId, decision, confidence, modelCallId, now);
    }
}
