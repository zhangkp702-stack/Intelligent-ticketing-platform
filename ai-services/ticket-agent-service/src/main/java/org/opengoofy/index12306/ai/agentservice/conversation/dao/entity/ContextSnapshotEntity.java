package org.opengoofy.index12306.ai.agentservice.conversation.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次模型调用实际装配上下文的可追溯元数据，不保存重复正文。
 */
@Getter
@Entity
@Table(name = "t_agent_context_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContextSnapshotEntity extends AgentBaseEntity {

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "conversation_id", nullable = false, length = 32)
    private String conversationId;

    @Column(name = "summary_id", length = 32)
    private String summaryId;

    @Column(name = "summary_version")
    private Integer summaryVersion;

    @Column(name = "summarized_through_sequence", nullable = false)
    private long summarizedThroughSequence;

    @Column(name = "message_from_sequence")
    private Long messageFromSequence;

    @Column(name = "message_through_sequence")
    private Long messageThroughSequence;

    @Column(name = "selected_message_ids", columnDefinition = "TEXT")
    private String selectedMessageIds;

    @Column(name = "estimated_token_count", nullable = false)
    private int estimatedTokenCount;

    @Column(name = "context_hash", nullable = false, length = 64)
    private String contextHash;

    private ContextSnapshotEntity(
            String requestId,
            String conversationId,
            String summaryId,
            Integer summaryVersion,
            long summarizedThroughSequence,
            Long messageFromSequence,
            Long messageThroughSequence,
            String selectedMessageIds,
            int estimatedTokenCount,
            String contextHash,
            Instant now) {
        super(now);
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.summaryId = summaryId;
        this.summaryVersion = summaryVersion;
        this.summarizedThroughSequence = summarizedThroughSequence;
        this.messageFromSequence = messageFromSequence;
        this.messageThroughSequence = messageThroughSequence;
        this.selectedMessageIds = selectedMessageIds;
        this.estimatedTokenCount = Math.max(0, estimatedTokenCount);
        this.contextHash = Objects.requireNonNull(contextHash, "contextHash");
    }

    /**
     * 创建一次上下文装配的元数据快照。
     *
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param summaryId 使用的摘要标识
     * @param summaryVersion 使用的摘要版本
     * @param summarizedThroughSequence 摘要覆盖到的消息序号
     * @param messageFromSequence 首条消息序号
     * @param messageThroughSequence 末条消息序号
     * @param selectedMessageIds 选中消息标识 JSON
     * @param estimatedTokenCount 估算 Token 数
     * @param contextHash 上下文内容哈希
     * @param now 创建时间
     * @return 上下文快照实体
     */
    public static ContextSnapshotEntity create(
            String requestId,
            String conversationId,
            String summaryId,
            Integer summaryVersion,
            long summarizedThroughSequence,
            Long messageFromSequence,
            Long messageThroughSequence,
            String selectedMessageIds,
            int estimatedTokenCount,
            String contextHash,
            Instant now) {
        // 快照只保存引用、范围和哈希，避免重复保存用户对话正文。
        return new ContextSnapshotEntity(
                requestId, conversationId, summaryId, summaryVersion, summarizedThroughSequence,
                messageFromSequence, messageThroughSequence, selectedMessageIds,
                estimatedTokenCount, contextHash, now);
    }
}
