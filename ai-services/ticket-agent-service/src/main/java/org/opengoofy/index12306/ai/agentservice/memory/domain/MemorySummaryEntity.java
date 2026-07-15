package org.opengoofy.index12306.ai.agentservice.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个主题在指定消息范围上的不可变摘要版本。
 */
@Getter
@Entity
@Table(name = "t_agent_memory_summary")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemorySummaryEntity extends AgentBaseEntity {

    @Column(name = "conversation_id", nullable = false, length = 32)
    private String conversationId;

    @Column(name = "topic_id", nullable = false, length = 32)
    private String topicId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "from_sequence", nullable = false)
    private long fromSequence;

    @Column(name = "through_sequence", nullable = false)
    private long throughSequence;

    @Column(name = "summary_content", nullable = false, columnDefinition = "TEXT")
    private String summaryContent;

    @Column(name = "structured_state", columnDefinition = "TEXT")
    private String structuredState;

    @Column(name = "source_message_count", nullable = false)
    private int sourceMessageCount;

    @Column(name = "provider_id", length = 64)
    private String providerId;

    @Column(name = "candidate_id", length = 128)
    private String candidateId;

    @Column(name = "model_id", length = 128)
    private String modelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MemorySummaryStatus status;

    private MemorySummaryEntity(
            String conversationId,
            String topicId,
            int versionNo,
            long fromSequence,
            long throughSequence,
            String summaryContent,
            String structuredState,
            int sourceMessageCount,
            String providerId,
            String candidateId,
            String modelId,
            Instant now) {
        super(now);
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.topicId = Objects.requireNonNull(topicId, "topicId");
        this.versionNo = versionNo;
        this.fromSequence = fromSequence;
        this.throughSequence = throughSequence;
        this.summaryContent = Objects.requireNonNull(summaryContent, "summaryContent");
        this.structuredState = structuredState;
        this.sourceMessageCount = sourceMessageCount;
        this.providerId = providerId;
        this.candidateId = candidateId;
        this.modelId = modelId;
        this.status = MemorySummaryStatus.ACTIVE;
    }

    /**
     * 创建覆盖确定消息范围的新活动摘要版本。
     *
     * @param conversationId 会话标识
     * @param topicId 主题标识
     * @param versionNo 摘要版本号
     * @param fromSequence 起始消息序号
     * @param throughSequence 结束消息序号
     * @param summaryContent 完整摘要正文
     * @param structuredState 结构化业务状态 JSON
     * @param sourceMessageCount 来源消息数
     * @param providerId 模型平台标识
     * @param candidateId 候选模型标识
     * @param modelId 平台模型标识
     * @param now 创建时间
     * @return 新摘要实体
     */
    public static MemorySummaryEntity active(
            String conversationId,
            String topicId,
            int versionNo,
            long fromSequence,
            long throughSequence,
            String summaryContent,
            String structuredState,
            int sourceMessageCount,
            String providerId,
            String candidateId,
            String modelId,
            Instant now) {
        // 新版本先以活动状态写入，旧版本随后在同一事务中标记为已替代。
        return new MemorySummaryEntity(
                conversationId, topicId, versionNo, fromSequence, throughSequence,
                summaryContent, structuredState, sourceMessageCount,
                providerId, candidateId, modelId, now);
    }

    /**
     * 将当前摘要标记为已被更高版本替代。
     *
     * @param now 替代时间
     */
    public void supersede(Instant now) {
        // 历史摘要保留用于审计和回溯，只改变其活动状态。
        this.status = MemorySummaryStatus.SUPERSEDED;
        touch(now);
    }
}
