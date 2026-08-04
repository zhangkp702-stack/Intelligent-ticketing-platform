package org.opengoofy.index12306.ai.agentservice.conversation.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName("t_agent_model_call")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelCallEntity extends AgentBaseEntity {

    /**
     * 本次模型调用所属的请求标识。
     */
    private String requestId;

    /**
     * 本次模型调用所属的会话标识。
     */
    private String conversationId;

    /**
     * 本次模型调用关联的问答轮次标识。
     */
    private String turnId;

    /**
     * 模型在当前调用链中承担的业务角色。
     */
    private ModelRole role;

    /**
     * 实际调用的模型服务商标识。
     */
    private String providerId;

    /**
     * 模型路由选中的候选标识。
     */
    private String candidateId;

    /**
     * 实际调用的模型标识。
     */
    private String modelId;

    /**
     * 当前业务角色下的调用尝试序号。
     */
    private int attemptNo;

    /**
     * 当前调用在模型降级候选链中的索引。
     */
    private int fallbackIndex;

    /**
     * 本次模型调用结果。
     */
    private ModelAttemptOutcome outcome;

    /**
     * 模型调用失败分类；调用成功时为空。
     */
    private ModelFailureCategory failureCategory;

    /**
     * 模型调用总耗时，单位为毫秒。
     */
    private long latencyMillis;

    /**
     * 模型调用消耗的提示词令牌数。
     */
    private Integer promptTokens;

    /**
     * 模型调用生成的补全令牌数。
     */
    private Integer completionTokens;

    /**
     * 模型调用消耗的总令牌数。
     */
    private Integer totalTokens;

    /**
     * 流式调用是否已经向客户端发送首个响应分片。
     */
    private boolean firstChunkEmitted;

    /**
     * 调用失败时捕获的异常类型名称。
     */
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
