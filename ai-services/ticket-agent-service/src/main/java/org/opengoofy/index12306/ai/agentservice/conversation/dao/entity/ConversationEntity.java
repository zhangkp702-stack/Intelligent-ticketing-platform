package org.opengoofy.index12306.ai.agentservice.conversation.dao.entity;

import org.opengoofy.index12306.ai.agentservice.conversation.enums.ConversationStatus;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * 用户与购票智能体的一次长期会话。
 */
@Getter
@TableName("t_agent_conversation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationEntity extends AgentBaseEntity {

    /**
     * 会话所属用户标识。
     */
    private String userId;

    /**
     * 会话展示标题。
     */
    private String title;

    /**
     * 会话当前状态。
     */
    private ConversationStatus status;

    /**
     * 会话内已分配的最大消息序号。
     */
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
        // 会话创建时仅初始化消息序号，摘要由独立异步任务维护。
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
     * 判断会话是否属于指定用户。
     *
     * @param expectedUserId 待校验用户标识
     * @return 属于该用户时返回 {@code true}
     */
    public boolean belongsTo(String expectedUserId) {
        return userId.equals(expectedUserId);
    }
}
