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
 * 用户与购票智能体的一次长期会话。
 */
@Getter
@Entity
@Table(name = "t_agent_conversation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationEntity extends AgentBaseEntity {

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "title", length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ConversationStatus status;

    @Column(name = "active_topic_id", length = 32)
    private String activeTopicId;

    @Column(name = "last_message_sequence", nullable = false)
    private long lastMessageSequence;

    private ConversationEntity(String userId, String title, Instant now) {
        super(now);
        this.userId = Objects.requireNonNull(userId, "userId");
        this.title = title;
        this.status = ConversationStatus.ACTIVE;
    }

    /**
     * 创建属于指定用户的活动会话。
     *
     * @param userId 用户标识
     * @param title 会话标题
     * @param now 创建时间
     * @return 新会话实体
     */
    public static ConversationEntity create(String userId, String title, Instant now) {
        // 会话创建时不预设主题，首轮主题由后续路由结果确定。
        return new ConversationEntity(userId, title, now);
    }

    /**
     * 在会话行锁保护下分配下一个严格递增的消息序号。
     *
     * @param now 分配时间
     * @return 新消息序号
     */
    public long nextMessageSequence(Instant now) {
        if (status != ConversationStatus.ACTIVE) {
            throw new IllegalStateException("非活动会话不能追加消息");
        }
        // 序号在会话聚合上递增，数据库唯一约束作为最终并发保护。
        lastMessageSequence++;
        touch(now);
        return lastMessageSequence;
    }

    /**
     * 将最近选中的主题设置为会话活动主题。
     *
     * @param topicId 主题标识
     * @param now 修改时间
     */
    public void activateTopic(String topicId, Instant now) {
        // 活动主题只保存后端已校验的主题标识，不能直接接受模型自由文本。
        this.activeTopicId = Objects.requireNonNull(topicId, "topicId");
        touch(now);
    }

    /**
     * 判断会话是否属于指定用户。
     *
     * @param expectedUserId 待校验用户标识
     * @return 属于该用户时返回 {@code true}
     */
    public boolean belongsTo(String expectedUserId) {
        return userId.equals(expectedUserId);
    }
}
