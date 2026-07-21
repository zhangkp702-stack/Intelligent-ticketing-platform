package org.opengoofy.index12306.ai.agentservice.infra.enums;

/**
 * 模型调用失败分类，用于决定是否降级以及是否影响熔断状态。
 */
public enum ModelFailureCategory {

    AUTHENTICATION(true, true, true),
    RATE_LIMIT(true, true, true),
    TIMEOUT(true, true, false),
    NETWORK(true, true, false),
    SERVER_ERROR(true, true, false),
    MODEL_UNAVAILABLE(true, true, false),
    PROVIDER_BUSY(true, false, false),
    CONTEXT_LENGTH(true, false, false),
    INVALID_REQUEST(false, false, false),
    CONTENT_POLICY(false, false, false),
    BUSINESS(false, false, false),
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
