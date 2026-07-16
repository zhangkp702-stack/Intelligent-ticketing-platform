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
 * 从一条用户问题到最终助手回答的完整执行轮次。
 */
@Getter
@Entity
@Table(name = "t_agent_turn")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TurnEntity extends AgentBaseEntity {

    @Column(name = "conversation_id", nullable = false, length = 32)
    private String conversationId;

    @Column(name = "topic_id", length = 32)
    private String topicId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "user_message_id", nullable = false, length = 32)
    private String userMessageId;

    @Column(name = "assistant_message_id", length = 32)
    private String assistantMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TurnStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_category", length = 64)
    private String failureCategory;

    private TurnEntity(String conversationId, String requestId, String userMessageId, Instant now) {
        super(now);
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.userMessageId = Objects.requireNonNull(userMessageId, "userMessageId");
        this.status = TurnStatus.RUNNING;
        this.startedAt = now;
    }

    /**
     * 创建等待主题路由和助手回答的新轮次。
     *
     * @param conversationId 会话标识
     * @param requestId 请求标识
     * @param userMessageId 用户消息标识
     * @param now 开始时间
     * @return 新轮次实体
     */
    public static TurnEntity start(String conversationId, String requestId, String userMessageId, Instant now) {
        // 请求 ID 在会话内唯一，用于网络重试时返回同一轮次。
        return new TurnEntity(conversationId, requestId, userMessageId, now);
    }

    /**
     * 将当前轮次绑定到主题路由最终选中的主题。
     *
     * @param selectedTopicId 选中主题标识
     * @param now 更新时间
     */
    public void assignTopic(String selectedTopicId, Instant now) {
        if (status != TurnStatus.RUNNING) {
            throw new IllegalStateException("只有运行中轮次可以绑定主题");
        }
        if (topicId != null && !topicId.equals(selectedTopicId)) {
            throw new IllegalStateException("轮次已经绑定其他主题");
        }
        this.topicId = Objects.requireNonNull(selectedTopicId, "selectedTopicId");
        touch(now);
    }

    /**
     * 使用最终助手消息完成本轮问答。
     *
     * @param messageId 助手消息标识
     * @param now 完成时间
     */
    public void complete(String messageId, Instant now) {
        if (status != TurnStatus.RUNNING) {
            throw new IllegalStateException("轮次已经结束");
        }
        if (topicId == null) {
            throw new IllegalStateException("完成轮次前必须确定主题");
        }
        // 完成状态与助手消息引用在同一事务中更新，避免出现无回答的完成轮次。
        this.assistantMessageId = Objects.requireNonNull(messageId, "messageId");
        this.status = TurnStatus.COMPLETED;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 记录本轮在生成最终回答前失败。
     *
     * @param category 稳定失败分类
     * @param now 失败时间
     */
    public void fail(String category, Instant now) {
        if (status != TurnStatus.RUNNING) {
            throw new IllegalStateException("轮次已经结束");
        }
        // 只保存稳定分类，不把可能含敏感正文的异常消息写入轮次表。
        this.failureCategory = category;
        this.status = TurnStatus.FAILED;
        this.finishedAt = now;
        touch(now);
    }

    /**
     * 在客户端主动断开流式连接时取消仍在运行的轮次。
     *
     * @param now 取消时间
     */
    public void cancel(Instant now) {
        if (status != TurnStatus.RUNNING) {
            throw new IllegalStateException("轮次已经结束");
        }
        // 取消不记录外部异常正文，仅通过终态区分客户端中止和服务失败。
        this.status = TurnStatus.CANCELLED;
        this.finishedAt = now;
        touch(now);
    }
}
