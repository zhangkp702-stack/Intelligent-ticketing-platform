package org.opengoofy.index12306.ai.agentservice.conversation.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelRole;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelAttemptOutcome;
import org.opengoofy.index12306.ai.agentservice.infra.enums.ModelFailureCategory;

import java.time.Instant;
import java.util.Objects;

/**
 * 不包含提示词和响应正文的持久化模型调用审计。
 */
@Getter
@Entity
@Table(name = "t_agent_model_call")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelCallEntity extends AgentBaseEntity {

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "conversation_id", length = 32)
    private String conversationId;

    @Column(name = "turn_id", length = 32)
    private String turnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private ModelRole role;

    @Column(name = "provider_id", nullable = false, length = 64)
    private String providerId;

    @Column(name = "candidate_id", nullable = false, length = 128)
    private String candidateId;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "fallback_index", nullable = false)
    private int fallbackIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private ModelAttemptOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_category", length = 64)
    private ModelFailureCategory failureCategory;

    @Column(name = "latency_millis", nullable = false)
    private long latencyMillis;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "first_chunk_emitted", nullable = false)
    private boolean firstChunkEmitted;

    @Column(name = "exception_type", length = 256)
    private String exceptionType;

    private ModelCallEntity(ModelCallData data, Instant now) {
        super(now);
        this.requestId = data.requestId();
        this.conversationId = data.conversationId();
        this.turnId = data.turnId();
        this.role = Objects.requireNonNull(data.role(), "role");
        this.providerId = Objects.requireNonNull(data.providerId(), "providerId");
        this.candidateId = Objects.requireNonNull(data.candidateId(), "candidateId");
        this.modelId = Objects.requireNonNull(data.modelId(), "modelId");
        this.attemptNo = data.attemptNo();
        this.fallbackIndex = data.fallbackIndex();
        this.outcome = Objects.requireNonNull(data.outcome(), "outcome");
        this.failureCategory = data.failureCategory();
        this.latencyMillis = Math.max(0, data.latencyMillis());
        this.promptTokens = data.promptTokens();
        this.completionTokens = data.completionTokens();
        this.totalTokens = data.totalTokens();
        this.firstChunkEmitted = data.firstChunkEmitted();
        this.exceptionType = data.exceptionType();
    }

    /**
     * 创建一条不含用户正文和密钥的模型调用审计。
     *
     * @param data 模型调用稳定元数据
     * @param now 创建时间
     * @return 模型调用审计实体
     */
    public static ModelCallEntity create(ModelCallData data, Instant now) {
        // 审计只接受结构化元数据，调用方无法通过该接口写入提示词或响应正文。
        return new ModelCallEntity(data, now);
    }

    /**
     * 持久化模型调用所需的稳定字段集合。
     *
     * @param requestId 请求标识
     * @param conversationId 会话标识
     * @param turnId 轮次标识
     * @param role 模型角色
     * @param providerId 平台标识
     * @param candidateId 候选模型标识
     * @param modelId 平台模型标识
     * @param attemptNo 尝试序号
     * @param fallbackIndex 降级链位置
     * @param outcome 调用结果
     * @param failureCategory 失败分类
     * @param latencyMillis 调用耗时
     * @param promptTokens 输入 Token 数
     * @param completionTokens 输出 Token 数
     * @param totalTokens 总 Token 数
     * @param firstChunkEmitted 是否输出流式首包
     * @param exceptionType 异常类型，不含异常正文
     */
    public record ModelCallData(
            String requestId,
            String conversationId,
            String turnId,
            ModelRole role,
            String providerId,
            String candidateId,
            String modelId,
            int attemptNo,
            int fallbackIndex,
            ModelAttemptOutcome outcome,
            ModelFailureCategory failureCategory,
            long latencyMillis,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            boolean firstChunkEmitted,
            String exceptionType) {
    }
}
