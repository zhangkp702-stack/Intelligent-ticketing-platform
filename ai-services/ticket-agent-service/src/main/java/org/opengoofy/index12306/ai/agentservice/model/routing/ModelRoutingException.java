package org.opengoofy.index12306.ai.agentservice.model.routing;

import org.opengoofy.index12306.ai.agentservice.model.config.ModelRole;

import java.util.List;

/**
 * 表示指定角色的全部可用候选模型均无法完成调用。
 */
public class ModelRoutingException extends RuntimeException {

    private final ModelRole role;
    private final List<String> attemptedCandidates;
    private final ModelFailureCategory failureCategory;

    /**
     * 创建模型路由异常并保留已尝试候选项和最终故障分类。
     *
     * @param message 安全的错误说明
     * @param role 模型角色
     * @param attemptedCandidates 实际尝试过的候选项
     * @param failureCategory 最终故障分类
     * @param cause 最后一次调用异常
     */
    public ModelRoutingException(
            String message,
            ModelRole role,
            List<String> attemptedCandidates,
            ModelFailureCategory failureCategory,
            Throwable cause) {
        super(message, cause);
        this.role = role;
        this.attemptedCandidates = List.copyOf(attemptedCandidates);
        this.failureCategory = failureCategory;
    }

    /**
     * 返回本次调用使用的模型角色。
     *
     * @return 模型角色
     */
    public ModelRole role() {
        return role;
    }

    /**
     * 返回实际尝试过的候选模型标识。
     *
     * @return 不可修改的候选项列表
     */
    public List<String> attemptedCandidates() {
        return attemptedCandidates;
    }

    /**
     * 返回路由终止时的最终故障分类。
     *
     * @return 最终故障分类
     */
    public ModelFailureCategory failureCategory() {
        return failureCategory;
    }
}
