package org.opengoofy.index12306.ai.agentservice.conversation.dao.entity;

import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageType;
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
 * 不因摘要成功而删除的原始对话或工具消息。
 */
@Getter
@Entity
@Table(name = "t_agent_message")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageEntity extends AgentBaseEntity {

    @Column(name = "conversation_id", nullable = false, length = 32)
    private String conversationId;

    @Column(name = "turn_id", length = 32)
    private String turnId;

    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private MessageRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 32)
    private MessageType messageType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_format", nullable = false, length = 32)
    private String contentFormat;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    private MessageEntity(
            String conversationId,
            long sequenceNo,
            MessageRole role,
            MessageType messageType,
            String content,
            String contentFormat,
            int tokenCount,
            String requestId,
            String idempotencyKey,
            Instant now) {
        super(now);
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.sequenceNo = sequenceNo;
        this.role = Objects.requireNonNull(role, "role");
        this.messageType = Objects.requireNonNull(messageType, "messageType");
        this.content = Objects.requireNonNull(content, "content");
        this.contentFormat = Objects.requireNonNull(contentFormat, "contentFormat");
        this.tokenCount = Math.max(0, tokenCount);
        this.requestId = requestId;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * 创建按会话序号排列的原始消息。
     *
     * @param conversationId 会话标识
     * @param sequenceNo 会话内消息序号
     * @param role 消息角色
     * @param messageType 消息业务类型
     * @param content 原始消息内容
     * @param contentFormat 内容格式
     * @param tokenCount 估算 Token 数
     * @param requestId 请求标识
     * @param idempotencyKey 幂等键
     * @param now 创建时间
     * @return 新消息实体
     */
    public static MessageEntity create(
            String conversationId,
            long sequenceNo,
            MessageRole role,
            MessageType messageType,
            String content,
            String contentFormat,
            int tokenCount,
            String requestId,
            String idempotencyKey,
            Instant now) {
        // 原始正文只在会话数据库保存，模型调用审计不会复制该内容。
        return new MessageEntity(
                conversationId, sequenceNo, role, messageType, content, contentFormat,
                tokenCount, requestId, idempotencyKey, now);
    }

    /**
     * 将消息关联到创建它的问答轮次。
     *
     * @param associatedTurnId 轮次标识
     * @param now 更新时间
     */
    public void attachTurn(String associatedTurnId, Instant now) {
        if (turnId != null && !turnId.equals(associatedTurnId)) {
            throw new IllegalStateException("消息已经关联其他轮次");
        }
        this.turnId = Objects.requireNonNull(associatedTurnId, "associatedTurnId");
        touch(now);
    }
}
