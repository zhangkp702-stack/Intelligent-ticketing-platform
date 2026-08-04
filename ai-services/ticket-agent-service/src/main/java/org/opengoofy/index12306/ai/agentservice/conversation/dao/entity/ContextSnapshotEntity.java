package org.opengoofy.index12306.ai.agentservice.conversation.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次模型调用实际装配上下文的可追溯元数据，不保存重复正文。
 */
@Getter
@TableName("t_agent_context_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContextSnapshotEntity extends AgentBaseEntity {

    /**
     * 触发本次模型上下文装配的请求标识。
     */
    private String requestId;

    /**
     * 本次上下文所属的会话标识。
     */
    private String conversationId;

    /**
     * 本次上下文使用的会话摘要标识；未使用摘要时为空。
     */
    private String summaryId;

    /**
     * 本次上下文使用的会话摘要版本；未使用摘要时为空。
     */
    private Integer summaryVersion;

    /**
     * 已被摘要覆盖的最大消息序号，未使用摘要时为零。
     */
    private long summarizedThroughSequence;

    /**
     * 本次上下文选取的首条原始消息序号。
     */
    private Long messageFromSequence;

    /**
     * 本次上下文选取的末条原始消息序号。
     */
    private Long messageThroughSequence;

    /**
     * 本次上下文选中的消息标识 JSON。
     */
    private String selectedMessageIds;

    /**
     * 本次实际装配上下文内容的哈希值，用于审计和一致性校验。
     */
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
            String contextHash,
            Instant now) {
        // 快照只保存引用、范围和哈希，避免重复保存用户对话正文。
        return new ContextSnapshotEntity(
                requestId, conversationId, summaryId, summaryVersion, summarizedThroughSequence,
                messageFromSequence, messageThroughSequence, selectedMessageIds,
                contextHash, now);
    }
}
