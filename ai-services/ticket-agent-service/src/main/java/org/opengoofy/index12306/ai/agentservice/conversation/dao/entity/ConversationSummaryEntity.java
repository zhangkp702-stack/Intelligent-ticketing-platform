package org.opengoofy.index12306.ai.agentservice.conversation.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * 保存一个会话当前唯一有效的累计摘要。
 */
@Getter
@TableName("t_agent_conversation_summary")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationSummaryEntity extends AgentBaseEntity {

    /**
     * 摘要所属的会话标识。
     */
    private String conversationId;

    /**
     * 模型生成的完整累计摘要正文。
     */
    private String summaryContent;

    /**
     * 从会话中提取的结构化业务状态 JSON。
     */
    private String structuredState;

    /**
     * 当前摘要已覆盖的最大消息序号。
     */
    private long summarizedThroughSequence;

    /**
     * 会话摘要的业务版本号。
     */
    private int summaryVersion;

    /**
     * 当前摘要累计覆盖的原始消息数量。
     */
    private int sourceMessageCount;

    /**
     * 生成当前摘要的模型服务商标识。
     */
    private String providerId;

    /**
     * 生成当前摘要的路由候选标识。
     */
    private String candidateId;

    /**
     * 生成当前摘要的模型标识。
     */
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
