package org.opengoofy.index12306.ai.agentservice.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * 保存一个会话当前唯一有效的累计摘要。
 */
@Getter
@Entity
@Table(name = "t_agent_conversation_summary")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationSummaryEntity extends AgentBaseEntity {

    @Column(name = "conversation_id", nullable = false, unique = true, length = 32)
    private String conversationId;

    @Column(name = "summary_content", nullable = false, columnDefinition = "TEXT")
    private String summaryContent;

    @Column(name = "structured_state", columnDefinition = "TEXT")
    private String structuredState;

    @Column(name = "summarized_through_sequence", nullable = false)
    private long summarizedThroughSequence;

    @Column(name = "summary_version", nullable = false)
    private int summaryVersion;

    @Column(name = "source_message_count", nullable = false)
    private int sourceMessageCount;

    @Column(name = "provider_id", length = 64)
    private String providerId;

    @Column(name = "candidate_id", length = 128)
    private String candidateId;

    @Column(name = "model_id", length = 128)
    private String modelId;

    private ConversationSummaryEntity(String conversationId, Instant now) {
        super(now);
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.summaryContent = "";
    }

    /**
     * 为尚无摘要的会话创建唯一摘要行。
     *
     * @param conversationId 会话标识
     * @param now 创建时间
     * @return 空的会话摘要
     */
    public static ConversationSummaryEntity empty(String conversationId, Instant now) {
        // 空摘要的边界为零，后续上下文仍会加载该会话的原始消息。
        return new ConversationSummaryEntity(conversationId, now);
    }

    /**
     * 用模型生成的最新累计结果原地更新会话摘要。
     *
     * @param expectedVersion 领取任务时观察到的摘要版本
     * @param throughSequence 本次摘要覆盖到的消息序号
     * @param summaryContent 新的完整累计摘要
     * @param structuredState 新的结构化业务状态
     * @param sourceMessageCount 本次累计处理的消息数量
     * @param providerId 模型平台标识
     * @param candidateId 候选模型标识
     * @param modelId 实际模型标识
     * @param now 更新时间
     */
    public void replace(
            int expectedVersion,
            long throughSequence,
            String summaryContent,
            String structuredState,
            int sourceMessageCount,
            String providerId,
            String candidateId,
            String modelId,
            Instant now) {
        if (summaryVersion != expectedVersion) {
            throw new IllegalStateException("会话摘要版本已变化");
        }
        if (throughSequence < summarizedThroughSequence) {
            throw new IllegalStateException("会话摘要边界不能后退");
        }
        // 只更新当前行，不保留旧摘要版本，确保每个会话始终只有一份摘要。
        this.summaryContent = Objects.requireNonNull(summaryContent, "summaryContent");
        this.structuredState = structuredState;
        this.summarizedThroughSequence = throughSequence;
        this.summaryVersion++;
        this.sourceMessageCount += sourceMessageCount;
        this.providerId = providerId;
        this.candidateId = candidateId;
        this.modelId = modelId;
        touch(now);
    }
}
