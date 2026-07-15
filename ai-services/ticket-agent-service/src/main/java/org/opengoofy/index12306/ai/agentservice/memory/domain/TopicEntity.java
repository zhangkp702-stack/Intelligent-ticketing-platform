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
 * 一个会话中可被主题路由器重复选中的业务主题。
 */
@Getter
@Entity
@Table(name = "t_agent_topic")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TopicEntity extends AgentBaseEntity {

    @Column(name = "conversation_id", nullable = false, length = 32)
    private String conversationId;

    @Column(name = "topic_key", nullable = false, length = 64)
    private String topicKey;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "short_summary", length = 1000)
    private String shortSummary;

    @Column(name = "structured_state", columnDefinition = "TEXT")
    private String structuredState;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TopicStatus status;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Column(name = "summary_version", nullable = false)
    private int summaryVersion;

    @Column(name = "summarized_through_sequence", nullable = false)
    private long summarizedThroughSequence;

    private TopicEntity(String conversationId, String topicKey, String title, Instant now) {
        super(now);
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.topicKey = Objects.requireNonNull(topicKey, "topicKey");
        this.title = Objects.requireNonNull(title, "title");
        this.status = TopicStatus.ACTIVE;
        this.lastActiveAt = now;
    }

    /**
     * 创建一个尚未生成摘要的新主题。
     *
     * @param conversationId 所属会话标识
     * @param topicKey 会话内稳定主题键
     * @param title 主题标题
     * @param now 创建时间
     * @return 新主题实体
     */
    public static TopicEntity create(String conversationId, String topicKey, String title, Instant now) {
        // 主题键由后端生成或规范化，数据库唯一约束防止同会话重复创建。
        return new TopicEntity(conversationId, topicKey, title, now);
    }

    /**
     * 记录本轮再次使用该主题的时间。
     *
     * @param now 活跃时间
     */
    public void markActive(Instant now) {
        if (status == TopicStatus.ARCHIVED) {
            throw new IllegalStateException("已归档主题不能重新激活");
        }
        // 已完成主题被用户继续追问时恢复为活动状态。
        this.status = TopicStatus.ACTIVE;
        this.lastActiveAt = now;
        touch(now);
    }

    /**
     * 应用一个连续的新摘要版本并推进已压缩消息边界。
     *
     * @param newVersion 新摘要版本号
     * @param throughSequence 摘要覆盖到的消息序号
     * @param newShortSummary 用于主题路由的短摘要
     * @param newStructuredState 结构化业务状态 JSON
     * @param now 更新时间
     */
    public void applySummary(
            int newVersion,
            long throughSequence,
            String newShortSummary,
            String newStructuredState,
            Instant now) {
        if (newVersion != summaryVersion + 1) {
            throw new IllegalStateException("摘要版本必须连续递增");
        }
        if (throughSequence < summarizedThroughSequence) {
            throw new IllegalStateException("摘要消息边界不能回退");
        }
        // 主题表只保存路由所需短摘要，完整摘要正文保存在独立版本表中。
        this.summaryVersion = newVersion;
        this.summarizedThroughSequence = throughSequence;
        this.shortSummary = newShortSummary;
        this.structuredState = newStructuredState;
        touch(now);
    }
}
