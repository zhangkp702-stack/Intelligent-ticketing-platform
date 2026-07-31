package org.opengoofy.index12306.ai.agentservice.conversation.dao.entity;

import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageRole;
import org.opengoofy.index12306.ai.agentservice.conversation.enums.MessageType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * 不因摘要成功而删除的原始对话或工具消息。
 */
@Getter
@TableName("t_agent_message")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageEntity extends AgentBaseEntity {

    private String conversationId;

    private String turnId;

    private long sequenceNo;

    private MessageRole role;

    private MessageType messageType;

    private String content;

    private String contentFormat;

    private int tokenCount;

    private String requestId;

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
