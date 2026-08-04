package org.opengoofy.index12306.ai.agentservice.infra.enums;

/**
 * 模型调用失败分类，用于决定是否降级以及是否影响熔断状态。
 */
public enum ModelFailureCategory {

    /** 模型服务认证失败。 */
    AUTHENTICATION(true, true, true),
    /** 模型服务触发限流。 */
    RATE_LIMIT(true, true, true),
    /** 模型调用超时。 */
    TIMEOUT(true, true, false),
    /** 调用模型服务时发生网络异常。 */
    NETWORK(true, true, false),
    /** 模型服务返回服务端错误。 */
    SERVER_ERROR(true, true, false),
    /** 请求的模型当前不可用。 */
    MODEL_UNAVAILABLE(true, true, false),
    /** 模型提供商暂时繁忙。 */
    PROVIDER_BUSY(true, false, false),
    /** 输入上下文超过模型允许长度。 */
    CONTEXT_LENGTH(true, false, false),
    /** 请求参数不符合模型服务要求。 */
    INVALID_REQUEST(false, false, false),
    /** 请求触发模型内容安全策略。 */
    CONTENT_POLICY(false, false, false),
    /** 业务校验或业务流程失败。 */
    BUSINESS(false, false, false),
    /** 无法归类的模型调用失败。 */
    UNKNOWN(true, false, false);

    private final boolean fallbackAllowed;
    private final boolean countsTowardCircuit;
    private final boolean opensImmediately;

    ModelFailureCategory(boolean fallbackAllowed, boolean countsTowardCircuit, boolean opensImmediately) {
        this.fallbackAllowed = fallbackAllowed;
        this.countsTowardCircuit = countsTowardCircuit;
        this.opensImmediately = opensImmediately;
    }

    /**
     * 判断本次失败后是否允许尝试下一个候选模型。
     *
     * @return 允许降级时返回 {@code true}
     */
    public boolean fallbackAllowed() {
        return fallbackAllowed;
    }

    /**
     * 判断本次失败是否应累计到连续失败计数。
     *
     * @return 应累计熔断失败次数时返回 {@code true}
     */
    public boolean countsTowardCircuit() {
        return countsTowardCircuit;
    }

    /**
     * 判断本次失败是否应立即打开熔断器。
     *
     * @return 应立即熔断时返回 {@code true}
     */
    public boolean opensImmediately() {
        return opensImmediately;
    }
}
