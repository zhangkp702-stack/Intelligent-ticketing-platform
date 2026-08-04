package org.opengoofy.index12306.ai.agentservice.action.service;

import org.opengoofy.index12306.ai.agentservice.action.dto.ClaimedAction;
import org.opengoofy.index12306.ai.agentservice.action.service.ActionStateService.ExecutionLease;

import java.util.function.Supplier;

/**
 * 协调真实写执行权的领取、心跳和失效中止。
 */
public interface ActionExecutionLeaseCoordinator {

    /**
     * 为确认事务已经入队的执行记录领取数据库租约。
     *
     * @param action 已消费确认令牌的操作快照
     * @return 本次执行租约
     */
    ExecutionLease start(ClaimedAction action);

    /**
     * 在真实写调用期间周期续租，执行权失效时拒绝旧实例继续提交结果。
     *
     * @param lease 当前执行租约
     * @param operation 受保护的真实写调用
     * @param <T> 调用结果类型
     * @return 真实写调用结果
     */
    <T> T guard(ExecutionLease lease, Supplier<T> operation);
}
